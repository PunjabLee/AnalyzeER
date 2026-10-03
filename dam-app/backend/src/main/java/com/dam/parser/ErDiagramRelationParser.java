package com.dam.parser;

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
 *   jf_sales_order ||--o| jf_sales_order_cus : "1:1 [命名推断] cus.sales_order_id"
 * </pre>
 * Only lines matching {@code <table> <symbol> <table> : "label"} are taken; entity-definition
 * lines ({@code jf_x { bigint id PK "..." }}) and comments never match. The FK column candidates
 * are lifted from {@code alias.col} tokens; those after {@code ->} become the target column.
 * Catalog validation (endpoints exist / child really owns the column) is the caller's job.
 */
public final class ErDiagramRelationParser {

    private static final Pattern FENCE = Pattern.compile("^```\\s*mermaid\\s*$");
    private static final Pattern ANY_FENCE = Pattern.compile("^```");
    /** &lt;table&gt; &lt;symbol&gt; &lt;table&gt; : "label"  (symbol is a non-space run like ||--o{) */
    private static final Pattern RELATION = Pattern.compile(
            "^\\s*([A-Za-z_][A-Za-z0-9_]*)\\s+(\\S+)\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*:\\s*\"(.*)\"\\s*$");
    /** alias.col token; both parts at least 3 chars to avoid matching version-like fragments */
    private static final Pattern ALIAS_COL = Pattern.compile(
            "([A-Za-z_][A-Za-z0-9_]{2,})\\.([A-Za-z_][A-Za-z0-9_]{2,})");
    private static final Pattern BRACKET = Pattern.compile("\\[([^\\]]+)\\]");
    private static final Pattern CROSS_DOMAIN = Pattern.compile("跨域\\s*[·\\-]?\\s*(D\\d{2})");

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
            // entity-definition lines also start with a table name but their "symbol" is "{"
            if (symbol.startsWith("{") || !isRelationshipSymbol(symbol)) {
                continue;
            }
            String cardinality = cardinalityOf(symbol);
            String evidence = evidenceOf(label);
            double confidence = confidenceOf(evidence);
            ParsedErRelation r = new ParsedErRelation(left, right, cardinality, evidence, confidence, label);
            r.setCrossDomain(crossDomainOf(label));
            fillColumns(r, label);
            out.add(r);
        }
        return out;
    }

    private static boolean isRelationshipSymbol(String s) {
        return s.contains("--") || s.contains("..");
    }

    /** only three symbols actually occur: {@code ||--o{} }, {@code ||--o|}, {@code |o--o{}} (child = right). */
    private static String cardinalityOf(String symbol) {
        return symbol.equals("||--o|") ? "1:1" : "1:N";
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
                if (tok.contains(keywordFor(cand)) && rank(cand) < bestRank) {
                    best = cand;
                    bestRank = rank(best);
                }
            }
        }
        return best;
    }

    private static String keywordFor(String level) {
        return switch (level) {
            case "注释明示" -> "注释明示";
            case "索引佐证" -> "索引";
            case "字段命名" -> "命名推断";
            case "业务语义推断" -> "语义";
            default -> "待确认";
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

    /** split label on the first {@code ->}: alias.col before it are FK candidates, after is the target col. */
    private static void fillColumns(ParsedErRelation r, String label) {
        int arrow = label.indexOf("->");
        String before = arrow >= 0 ? label.substring(0, arrow) : label;
        collectCols(r.getFromColumnCandidates(), before);
        if (arrow >= 0) {
            List<String> toCols = new ArrayList<>();
            collectCols(toCols, label.substring(arrow + 2));
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
