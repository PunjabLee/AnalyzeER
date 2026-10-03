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
 * (e.g. {@code }o--o{}) is M:N. Cardinality/evidence are derived here; catalog validation and the
 * actual child/parent assignment happen in the ingestion service, which never trusts prose.
 */
public final class ErDiagramRelationParser {

    private static final Pattern FENCE = Pattern.compile("^```\\s*mermaid\\s*$");
    private static final Pattern ANY_FENCE = Pattern.compile("^```");
    /** &lt;table&gt; &lt;symbol&gt; &lt;table&gt; : "label"  (symbol is a non-space marker run) */
    private static final Pattern RELATION = Pattern.compile(
            "^\\s*([A-Za-z_][A-Za-z0-9_]*)\\s+(\\S+)\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*:\\s*\"(.*)\"\\s*$");
    /** alias.col token; NO minimum length (ownership is enforced by the catalog check, not a heuristic) */
    private static final Pattern ALIAS_COL = Pattern.compile(
            "([A-Za-z_][A-Za-z0-9_]*)\\.([A-Za-z_][A-Za-z0-9_]+)");
    private static final Pattern BRACKET = Pattern.compile("\\[([^\\]]+)\\]");
    private static final Pattern CROSS_DOMAIN = Pattern.compile("跨域\\s*[·\\-]?\\s*(D\\d{2})");
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
                continue;   // entity-definition line or unsupported marker
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
     * crow-foot presence ({@code }} on left, {@code &#123;} on right) to a {@link Kind}. Returns null
     * when the token is not a recognised relationship symbol.
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
        if (leftMarker.isEmpty() || rightMarker.isEmpty()
                || !leftMarker.matches("[|o}]+") || !rightMarker.matches("[|o{]+")) {
            return null;
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
        };
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
            case "字段命名" -> token.contains("命名推断") || token.contains("字段命名") || token.contains("自关联");
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
     * Splits the label on the first target separator ({@code ->}/{@code ↔}/{@code =}); {@code alias.col}
     * tokens before it are FK-column candidates, the first one after becomes the target column.
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
        collectCols(r.getFromColumnCandidates(), before);
        if (idx >= 0) {
            List<String> toCols = new ArrayList<>();
            collectCols(toCols, label.substring(idx + sepLen));
            if (!toCols.isEmpty()) {
                r.setToColumnCandidate(toCols.get(0));
            }
        }
    }

    private static void collectCols(List<String> sink, String text) {
        Matcher ac = ALIAS_COL.matcher(text);
        while (ac.find()) {
            String col = ac.group(2);
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
