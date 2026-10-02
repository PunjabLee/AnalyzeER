package com.dam.parser;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Block-based DDL parser for MySQL (Navicat-style) dump files.
 *
 * <p>Deliberately mirrors the counting logic of skills/.../ddl_census.ps1 so that parsed
 * table/column counts match the census numbers already recorded in er-model docs
 * (test_erp.sql: 1322 tables; jf_sales_order: 90 physical columns).
 *
 * <p>Rationale (vs JSqlParser): the Navicat dump interleaves DROP TABLE / SET statements
 * and uses MySQL-8 specifics (COLLATE, ROW_FORMAT, USING BTREE); a line/block scanner is
 * far more robust here and reproduces census exactly. Relation/FK metadata is NOT derivable
 * from DDL (0 foreign keys in this DB) and is ingested separately from er-model docs (M1).
 */
public final class DdlParser {

    private static final Pattern CREATE = Pattern.compile(
            "^\\s*CREATE\\s+TABLE\\s+(IF\\s+NOT\\s+EXISTS\\s+)?`?([^`\\s(]+)`?",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern COL_LINE = Pattern.compile("^\\s*`");
    // a column line that is actually an index/key/constraint definition -> exclude
    private static final Pattern NON_COL = Pattern.compile(
            "^\\s*`[^`]*`\\s+(INDEX|KEY|UNIQUE|PRIMARY|CONSTRAINT|FULLTEXT|SPATIAL)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern COL_NAME_REST = Pattern.compile("^\\s*`([^`]+)`\\s*(.*)$");
    private static final Pattern PK_LINE = Pattern.compile("PRIMARY\\s+KEY\\s*\\(([^)]*)\\)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern PK_COL = Pattern.compile("`([^`]+)`");
    private static final Pattern BACKTICK = Pattern.compile("`([^`]+)`");
    private static final Pattern CLOSE_LINE = Pattern.compile("^\\s*\\)");

    private static final Pattern TYPE_HEAD = Pattern.compile(
            "^([a-zA-Z]+\\s*(?:\\([^)]*\\))?(?:\\s+unsigned)?(?:\\s+zerofill)?)(?:\\s|$)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern DEFAULT_PAT = Pattern.compile(
            "DEFAULT\\s+('(?:[^']|'')*'|[A-Za-z0-9_.+-]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern COMMENT_PAT = Pattern.compile(
            "COMMENT\\s+('(?:[^']|'')*')", Pattern.CASE_INSENSITIVE);
    private static final Pattern CHARSET_PAT = Pattern.compile(
            "CHARACTER\\s+SET\\s*=?\\s*([a-zA-Z0-9_]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern COLLATE_PAT = Pattern.compile(
            "COLLATE\\s*=?\\s*([a-zA-Z0-9_]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern TABLE_COMMENT_PAT = Pattern.compile(
            "COMMENT\\s*=\\s*'((?:[^']|'')*)'", Pattern.CASE_INSENSITIVE);

    private DdlParser() { }

    public static DdlParserResult parse(List<String> lines) {
        List<ParsedTable> tables = new ArrayList<>();
        List<String> rawNames = new ArrayList<>();
        int n = lines.size();
        for (int i = 0; i < n; i++) {
            Matcher cm = CREATE.matcher(lines.get(i));
            if (!cm.find()) {
                continue;
            }
            String name = cm.group(2).toLowerCase();
            rawNames.add(name);

            int end = i + 1;
            while (end < n && !CLOSE_LINE.matcher(lines.get(end)).find()) {
                end++;
            }
            String closeLine = end < n ? lines.get(end) : "";
            List<String> block = lines.subList(i + 1, Math.max(i + 1, Math.min(end, n)));

            ParsedTable table = new ParsedTable(name);
            table.setStartLine(i + 1);
            parseBlock(table, block, closeLine);
            tables.add(table);
        }

        Set<String> unique = new LinkedHashSet<>(rawNames);
        return new DdlParserResult(tables, rawNames.size(), unique.size());
    }

    private static void parseBlock(ParsedTable table, List<String> block, String closeLine) {
        // 1. PK columns (may be a separate PRIMARY KEY (...) line)
        Set<String> pkCols = new LinkedHashSet<>();
        for (String line : block) {
            Matcher pk = PK_LINE.matcher(line);
            if (pk.find()) {
                table.setHasPk(true);
                Matcher cols = PK_COL.matcher(pk.group(1));
                while (cols.find()) {
                    pkCols.add(cols.group(1).toLowerCase());
                }
            }
        }

        // 2. columns
        for (String line : block) {
            if (!COL_LINE.matcher(line).find()) {
                continue;
            }
            if (NON_COL.matcher(line).find()) {
                continue;
            }
            Matcher nm = COL_NAME_REST.matcher(line);
            if (!nm.find()) {
                continue;
            }
            String colName = nm.group(1).toLowerCase();
            String rest = stripTrailingComma(nm.group(2));

            ParsedColumn col = new ParsedColumn(colName);
            Matcher th = TYPE_HEAD.matcher(rest);
            if (th.find()) {
                col.setType(th.group(1).trim().replaceAll("\\s+", " "));
            } else {
                col.setType(rest);
            }
            String upper = rest.toUpperCase();
            col.setNotNull(upper.contains("NOT NULL"));
            col.setAutoIncrement(upper.contains("AUTO_INCREMENT"));

            Matcher d = DEFAULT_PAT.matcher(rest);
            if (d.find()) {
                col.setDefaultValue(unquote(d.group(1)));
            }
            Matcher c = COMMENT_PAT.matcher(rest);
            if (c.find()) {
                col.setComment(unquote(c.group(1)));
            }
            if (pkCols.contains(colName)) {
                col.setPrimaryKey(true);
            }
            table.getColumns().add(col);
        }

        if (!table.isHasPk() && !pkCols.isEmpty()) {
            table.setHasPk(true);
        }

        // 3. table-level comment / charset / collate from the closing line
        Matcher tcm = TABLE_COMMENT_PAT.matcher(closeLine);
        if (tcm.find()) {
            table.setTableComment(unescapeSql(tcm.group(1)));
        }
        Matcher cs = CHARSET_PAT.matcher(closeLine);
        if (cs.find()) {
            table.setCharset(cs.group(1));
        }
        Matcher co = COLLATE_PAT.matcher(closeLine);
        if (co.find()) {
            table.setCollate(co.group(1));
        }
    }

    private static String stripTrailingComma(String s) {
        String t = s.trim();
        if (t.endsWith(",")) {
            t = t.substring(0, t.length() - 1).trim();
        }
        return t;
    }

    private static String unquote(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        if (t.equalsIgnoreCase("NULL")) {
            return null;
        }
        if (t.length() >= 2 && t.startsWith("'") && t.endsWith("'")) {
            return unescapeSql(t.substring(1, t.length() - 1));
        }
        return t;
    }

    private static String unescapeSql(String s) {
        return s == null ? null : s.replace("''", "'").replace("\\'", "'");
    }

    /**
     * Extracts the first back-ticked identifier list from an index line (helper for later phases).
     */
    public static List<String> backtickTokens(String s) {
        List<String> out = new ArrayList<>();
        Matcher m = BACKTICK.matcher(s);
        while (m.find()) {
            out.add(m.group(1));
        }
        return out;
    }
}
