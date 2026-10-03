package com.dam.parser;

import com.dam.parser.ParsedErRelation.Kind;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Channel-1 parser: extracts inferred relation edges from the {@code erDiagram} blocks of
 * {@code er-model/01-ER图/*.md} (PLAN §1.1 双通道 / D1). These Mermaid relation lines are the
 * machine-readable backbone of the ER evidence: both endpoints are written as REAL table names
 * (resolvable against the catalog — no prose Chinese labels), and each carries a cardinality
 * symbol plus an explicit {@code [证据]} tag — the two things channel-2 (logical FK columns)
 * cannot provide.
 *
 * <p>Consumed shape (inside a ```mermaid fenced block):
 * <pre>
 *   jf_customer ||--o{ jf_sales_order : "1:N [命名推断] order.customer_id -&gt; customer.id (NOT NULL)"
 *   jf_product_color_coordina_mapping }o--o{ jf_product : "M:N [命名推断·编码] pccm.sku -&gt; product.sku"
 * </pre>
 * The relationship symbol's LEFT and RIGHT crow-foot markers decide the {@link Kind}: {@code }} on
 * the left or {@code &#123;} on the right mark the "many" (FK-owning) side; a line whose markers are
 * both "one/optional" (e.g. {@code ||--o|}) is 1:1 with an ambiguous FK side; one with BOTH many
 * (e.g. {@code }o--o{}) is M:N.
 *
 * <p><b>Two trust gates (review remediation S1-3 / S2-1):</b>
 * <ul>
 *   <li>a symbol missing one side's marker (author typo like {@code A ..o{ B}) is a real relation
 *       line but its direction is undecidable → {@link Kind#UNSUPPORTED_SYMBOL} (counted, never
 *       silently dropped, so {@code ErReport} reconciles against the corpus);</li>
 *   <li>when the label itself carries a cardinality token (e.g. {@code "N:N …"}) that contradicts
 *       the symbol's derived kind, the line is untrustworthy → {@link Kind#AMBIGUOUS} (never builds
 *       or resolves an edge). Cardinality is only trusted when symbol and label agree.</li>
 * </ul>
 * Catalog validation and the actual child/parent assignment happen in the ingestion service, which
 * never trusts prose.
 */
public final class ErDiagramRelationParser {

    private static final Pattern FENCE = Pattern.compile("^```\\s*mermaid\\s*$");
    private static final Pattern ANY_FENCE = Pattern.compile("^```");
    /** &lt;table&gt; &lt;symbol&gt; &lt;table&gt; : "label"  (symbol is a non-space marker run) */
    private static final Pattern RELATION = Pattern.compile(
            "^\\s*([A-Za-z_][A-Za-z0-9_]*)\\s+(\\S+)\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*:\\s*\"(.*)\"\\s*$");
    /** alias.col token; alias may be short (e.g. {@code rs.}) — ownership is enforced by the catalog, not length */
    private static final Pattern ALIAS_COL = Pattern.compile(
            "([A-Za-z_][A-Za-z0-9_]*)\\.([A-Za-z_][A-Za-z0-9_]+)");
    /** bare FK-shaped column token for the no-alias fallback (e.g. {@code customer_reconciliation_id}) */
    private static final Pattern BARE_FK = Pattern.compile(
            "\\b([A-Za-z][A-Za-z0-9_]*_(?:id|code))\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern BRACKET = Pattern.compile("\\[([^\\]]+)\\]");
    private static final Pattern CROSS_DOMAIN = Pattern.compile("跨域\\s*[·\\-]?\\s*(D\\d{2})");
    /** leading cardinality token in the label, e.g. {@code N:N}, {@code 0..1:N}, {@code 1:1} */
    private static final Pattern LABEL_CARD = Pattern.compile(
            "^\\s*([0-9MmNn.]+)\\s*[:：]\\s*([0-9MmNn.]+)");
    /** target-side separators, in priority order: arrow / bi-arrow / full-width & half-width equals */
    private static final String[] TARGET_SEPARATORS = {"->", "↔", "＝", "="};

    private ErDiagramRelationParser() { }

    public static List<ParsedErRelation> parse(List<String> lines) {
        List<ParsedErRelation> out = new ArrayList<>();
        boolean inMermaid = false;
        for (String raw : lines) {
            String line = raw.trim();
            if (ANY_FENCE.matcher(line).find()) {
                inMermaid = FENCE.matcher(line).matches();
                continue;
            }
            if (!inMermaid) {
                continue;
            }
            Matcher m = RELATION.matcher(line);
            if (!m.matches()) {
                continue;
            }
            String left = m.group(1);
            String symbol = m.group(2);
            String right = m.group(3);
            String label = m.group(4);
            Kind kind = classify(symbol);
            if (kind == null) {
                continue;   // not a relationship symbol at all (entity-definition line etc.)
            }
            // S1-3: cross-check the symbol against the label's own cardinality token
            if (kind != Kind.UNSUPPORTED_SYMBOL && labelCardinalityClashes(kind, label)) {
                kind = Kind.AMBIGUOUS;
            }
            String cardinality = cardinalityOf(kind);
            String evidence = evidenceOf(label);
            ParsedErRelation r = new ParsedErRelation(left, right, kind, cardinality, evidence,
                    confidenceOf(evidence), label);
            r.setCrossDomain(crossDomainOf(label));
            fillColumns(r, label);
            out.add(r);
        }
        return out;
    }

    /**
     * Splits the symbol into left/right markers on the {@code --}/{@code ..} connector and maps the
     * crow-foot presence ({@code }} on left, {@code &#123;} on right) to a {@link Kind}. A marker side
     * that is empty (author omitted it) yields {@link Kind#UNSUPPORTED_SYMBOL}; returns null only
     * when the token is not a recognised relationship symbol at all.
     */
    private static Kind classify(String symbol) {
        int sep = symbol.indexOf("--");
        if (sep < 0) {
            sep = symbol.indexOf("..");
        }
        if (sep < 0) {
            return null;
        }
        String leftMarker = symbol.substring(0, sep);
        String rightMarker = symbol.substring(sep + 2);
        if (!leftMarker.matches("[|o}]*") || !rightMarker.matches("[|o{]*")) {
            return null;   // illegal characters: not a relationship symbol
        }
        if (leftMarker.isEmpty() || rightMarker.isEmpty()) {
            return Kind.UNSUPPORTED_SYMBOL;   // one side's marker missing → direction undecidable (S2-1)
        }
        boolean leftMany = leftMarker.contains("}");
        boolean rightMany = rightMarker.contains("{");
        if (leftMany && rightMany) {
            return Kind.MANY_TO_MANY;
        }
        if (rightMany) {
            return Kind.ONE_TO_MANY;
        }
        if (leftMany) {
            return Kind.MANY_TO_ONE;
        }
        return Kind.ONE_TO_ONE;
    }

    private static String cardinalityOf(Kind kind) {
        return switch (kind) {
            case ONE_TO_MANY, MANY_TO_ONE -> "1:N";
            case ONE_TO_ONE -> "1:1";
            case MANY_TO_MANY -> "N:M";
            case AMBIGUOUS, UNSUPPORTED_SYMBOL -> null;
        };
    }

    /**
     * True when the label opens with a cardinality token whose left/right "many-ness" disagrees with
     * the symbol-derived kind. Absent / unparseable token → no clash (trust the symbol).
     */
    private static boolean labelCardinalityClashes(Kind kind, String label) {
        Matcher lc = LABEL_CARD.matcher(label);
        if (!lc.find()) {
            return false;
        }
        boolean leftMany = isManySide(lc.group(1));
        boolean rightMany = isManySide(lc.group(2));
        boolean symLeftMany;
        boolean symRightMany;
        switch (kind) {
            case ONE_TO_MANY -> { symLeftMany = false; symRightMany = true; }
            case MANY_TO_ONE -> { symLeftMany = true; symRightMany = false; }
            case ONE_TO_ONE -> { symLeftMany = false; symRightMany = false; }
            case MANY_TO_MANY -> { symLeftMany = true; symRightMany = true; }
            default -> { return false; }
        }
        return leftMany != symLeftMany || rightMany != symRightMany;
    }

    private static boolean isManySide(String token) {
        String t = token.toLowerCase();
        return t.indexOf('n') >= 0 || t.indexOf('m') >= 0 || t.indexOf('＊') >= 0 || t.indexOf('*') >= 0;
    }

    /** strongest five-level evidence keyword present in any non-跨域 bracket wins (er-model §六). */
    private static String evidenceOf(String label) {
        String best = "待确认";
        int bestRank = rank(best);
        Matcher b = BRACKET.matcher(label);
        while (b.find()) {
            String tok = b.group(1);
            if (tok.contains("跨域")) {
                continue;
            }
            for (String cand : new String[]{"注释明示", "索引佐证", "字段命名", "业务语义推断"}) {
                if (hasKeyword(tok, cand) && rank(cand) < bestRank) {
                    best = cand;
                    bestRank = rank(best);
                }
            }
        }
        return best;
    }

    private static boolean hasKeyword(String token, String level) {
        return switch (level) {
            case "注释明示" -> token.contains("注释明示");
            case "索引佐证" -> token.contains("索引");
            case "字段命名" -> token.contains("命名推断") || token.contains("字段命名");
            case "业务语义推断" -> token.contains("语义");
            default -> token.contains("待确认");
        };
    }

    private static String crossDomainOf(String label) {
        Matcher b = BRACKET.matcher(label);
        while (b.find()) {
            Matcher cd = CROSS_DOMAIN.matcher(b.group(1));
            if (cd.find()) {
                return cd.group(1);
            }
        }
        return null;
    }

    /**
     * Splits the label on the first target separator ({@code ->}/{@code ↔}/{@code =}). FK-column
     * candidates come from the BEFORE segment: {@code alias.col} tokens whose column is not a bare
     * PK {@code id} (S2-2 — a {@code parent.id} target notation must not become a child FK), plus a
     * bare {@code *_id}/{@code *_code} fallback when no alias-qualified column is present (S2-4).
     * The target column comes from the AFTER segment and MAY be {@code id} (a parent PK).
     */
    private static void fillColumns(ParsedErRelation r, String label) {
        int idx = -1;
        int sepLen = 0;
        for (String sep : TARGET_SEPARATORS) {
            int i = label.indexOf(sep);
            if (i >= 0 && (idx < 0 || i < idx)) {
                idx = i;
                sepLen = sep.length();
            }
        }
        String before = idx >= 0 ? label.substring(0, idx) : label;
        List<String> candidates = r.getFromColumnCandidates();
        collectCols(candidates, before, false);
        if (candidates.isEmpty()) {
            collectBareFk(candidates, before);   // no alias.col at all → bare *_id/_code fallback
        }
        if (idx >= 0) {
            List<String> toCols = new ArrayList<>();
            collectCols(toCols, label.substring(idx + sepLen), true);
            if (!toCols.isEmpty()) {
                r.setToColumnCandidate(toCols.get(0));
            }
        }
    }

    /** collect {@code alias.col} column names; {@code forTarget} keeps {@code id}, FK-candidate mode drops bare {@code id}. */
    private static void collectCols(List<String> sink, String text, boolean forTarget) {
        Matcher ac = ALIAS_COL.matcher(text);
        while (ac.find()) {
            String col = ac.group(2);
            if (!forTarget && col.equalsIgnoreCase("id")) {
                continue;   // S2-2: a parent PK id is never the child's FK column name
            }
            if (!sink.contains(col)) {
                sink.add(col);
            }
        }
    }

    private static void collectBareFk(List<String> sink, String text) {
        Matcher bf = BARE_FK.matcher(text);
        while (bf.find()) {
            String col = bf.group(1);
            if (!sink.contains(col)) {
                sink.add(col);
            }
        }
    }

    /** five-level ladder rank: 1 strongest .. 4 weakest. */
    public static int rank(String evidenceLevel) {
        return switch (evidenceLevel) {
            case "注释明示" -> 1;
            case "索引佐证" -> 2;
            case "字段命名" -> 3;
            case "业务语义推断" -> 4;
            default -> 5;
        };
    }

    private static double confidenceOf(String evidenceLevel) {
        return switch (evidenceLevel) {
            case "注释明示" -> 0.8;
            case "索引佐证" -> 0.7;
            case "字段命名" -> 0.6;
            case "业务语义推断" -> 0.4;
            default -> 0.3;
        };
    }
}
