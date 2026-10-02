package com.dam.parser;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses er-model/00-总览与分组清单.md "四、A 级业务域分组清单" sections.
 *
 * <p>Expected shape (as produced by the mysql-ddl-data-modeling skill):
 * <pre>
 * ### D01 销售订单域（18）
 * jf_sales_order、jf_sales_order_async、...
 *
 * ### OT 其他无前缀业务/遗留表（17，纳入 A 级逐表处理，...）
 * country、entity1、...
 * </pre>
 *
 * <p>Pure line scanner (no Spring) so it is unit-testable against the real document.
 */
public final class ErModelCensusParser {

    /** ### D01 销售订单域（18）  /  ### OT 其他无前缀业务/遗留表（17，...） */
    private static final Pattern SECTION = Pattern.compile(
            "^### (D\\d{2}|OT)\\s+(.+?)（(\\d+)");

    private ErModelCensusParser() { }

    public static List<ParsedDomain> parse(List<String> lines) {
        List<ParsedDomain> domains = new ArrayList<>();
        ParsedDomain current = null;
        boolean listPending = false;
        for (String raw : lines) {
            String line = raw.trim();
            Matcher m = SECTION.matcher(line);
            if (m.find()) {
                current = new ParsedDomain(m.group(1), m.group(2).trim(), Integer.parseInt(m.group(3)));
                listPending = true;
                continue;
            }
            if (current == null) {
                continue;
            }
            if (line.isEmpty() || line.startsWith("#") || line.startsWith(">") || line.startsWith("|")) {
                // section header block interrupted by prose: keep waiting for the list line,
                // but a new "###"/"##" heading ends the pending state
                if (line.startsWith("##")) {
                    domains.add(current);
                    current = null;
                }
                continue;
            }
            if (listPending) {
                for (String t : line.split("、")) {
                    String name = t.trim().replaceAll("`", "");
                    if (!name.isEmpty()) {
                        current.getTables().add(name);
                    }
                }
                domains.add(current);
                current = null;
                listPending = false;
            }
        }
        if (current != null && !current.getTables().isEmpty()) {
            domains.add(current);
        }
        return domains;
    }
}
