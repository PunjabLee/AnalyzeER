package com.dam.parser;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts inferred relation edges from er-model/03-逻辑数据模型/*.md files.
 *
 * <p>Shape consumed (6-column field tables under numbered sections):
 * <pre>
 * ## 1. jf_sales_order — 销售单主表（90 列）
 * | 字段 | 类型 | 可空 | 默认 | 键 | 含义 |
 * | customer_id | bigint | N | | FK[jf_customer·命名推断] | 客户id |
 * </pre>
 *
 * <p>The key cell may carry: markdown bold ({@code **}), extra segments after further "·"
 * (int/编码/待确认 notes), parenthesised column hints ((order_no)/(code)), polymorphic
 * "A/B" targets and prose-only targets (库存明细). Only the 键 column is scanned;
 * legend text such as FK[目标表·依据] sits in non-field lines and never matches.
 */
public final class LogicalModelRelationParser {

    /**
     * Table-section headings come in three documented layouts:
     * {@code ## 1. jf_x — 说明} (D01), {@code ### 1. jf_x — 说明} (D02..D10/D15),
     * {@code ### jf_x（说明）} / {@code ### country（国家表）} (D11..D18, OT).
     * Chinese-numbered or prose headings (## 一、报价块 / ## 簇 A) never match because the
     * title must start with an ASCII identifier followed by a dash / bracket / space.
     */
    private static final Pattern HEADING = Pattern.compile("^#{2,4}\\s+(.*)$");
    private static final Pattern SECTION_NUM = Pattern.compile("^\\d+\\.\\s*");
    private static final Pattern SECTION_NAME = Pattern.compile(
            "^([A-Za-z][A-Za-z0-9_]*)(\\s*[\u2014\uff08(\\-]|$)");
    private static final Pattern FK_MARK = Pattern.compile("FK\\[([^\\]]+)\\]");
    /** ascii identifier in parens used as target-column hint, e.g. (order_no) */
    private static final Pattern COL_HINT = Pattern.compile("\\(([a-z][a-z0-9_]*)\\)");

    private LogicalModelRelationParser() { }

    public static List<ParsedRelation> parse(List<String> lines) {
        List<ParsedRelation> out = new ArrayList<>();
        String table = null;
        for (String raw : lines) {
            String line = raw.trim();
            Matcher hm = HEADING.matcher(line);
            if (hm.matches()) {
                String title = SECTION_NUM.matcher(hm.group(1)).replaceFirst("");
                Matcher nm2 = SECTION_NAME.matcher(title);
                if (nm2.find()) {
                    table = nm2.group(1);
                }
                // any heading ends the current table section unless it re-matches above
                continue;
            }
            if (table == null || !line.startsWith("|")) {
                continue;
            }
            String[] cells = line.split("\\|", -1); // -1: keep leading/trailing empty cells
            // layouts vary per document (6/7 columns, some tables omit the 默认 column),
            // so instead of pinning the 键 position, scan every cell for FK[...] marks
            if (cells.length < 6) {
                continue;
            }
            String col = cells[1].trim();
            if (col.isEmpty() || col.equals("字段") || !col.matches("[A-Za-z_][A-Za-z0-9_]*")) {
                continue;
            }
            for (int ci = 2; ci < cells.length - 1; ci++) {
                Matcher fm = FK_MARK.matcher(cells[ci]);
                while (fm.find()) {
                    String inner = fm.group(1);
                    String[] segs = inner.split("·");
                    String target = segs[0].trim();
                    String basis = segs.length > 1
                            ? inner.substring(inner.indexOf('·') + 1).replace("·", " ").trim()
                            : "";
                    ParsedRelation pr = new ParsedRelation(table, col, target, basis);
                    Matcher hint = COL_HINT.matcher(basis);
                    if (hint.find()) {
                        pr.setTargetColumn(hint.group(1));
                    }
                    out.add(pr);
                }
            }
        }
        return out;
    }
}
