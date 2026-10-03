package com.dam.parser;

import java.util.ArrayList;
import java.util.List;

/**
 * Parses {@code er-model/05-跨域核心关系总览.md} section 二 (核心实体清单·三分类) into a flat list
 * of core business entities. The three sub-sections map to the BOM categories:
 *
 * <pre>
 *   2.1 主数据 / 基础实体（Master Data）        -> MASTER
 *   2.2 交易 / 事务实体（Transactional）        -> TRANSACTIONAL
 *   2.3 配置 / 字典实体（Config & Dictionary）  -> CONFIG
 * </pre>
 *
 * <p>Each data row is a Markdown table with three cells: {@code 实体 | 域 | 说明}. The 实体 cell is
 * returned raw ({@link ParsedCoreEntity#expression()}); resolving it to concrete tables is done in
 * the service strictly by matching against the real physical catalog, so this parser makes no
 * guesses. Header/separator rows are skipped.
 */
public final class CoreEntityCatalogParser {

    private CoreEntityCatalogParser() { }

    /** one row of the 05 §二 core-entity table */
    public record ParsedCoreEntity(String category, String expression, String domainCode, String description) { }

    public static List<ParsedCoreEntity> parse(List<String> lines) {
        List<ParsedCoreEntity> out = new ArrayList<>();
        boolean inSection = false;
        String category = null;
        for (String raw : lines) {
            String line = raw.trim();
            if (line.startsWith("## ")) {
                // entering/leaving a top-level section (§二 ... §三)
                inSection = line.startsWith("## 二");
                continue;
            }
            if (!inSection) {
                continue;
            }
            if (line.startsWith("### ")) {
                category = categoryOf(line);
                continue;
            }
            if (category == null || !line.startsWith("|")) {
                continue;
            }
            String[] cells = splitRow(line);
            if (cells.length < 3) {
                continue;
            }
            String entity = cells[0];
            // skip header (实体) and separator (---) rows
            if (entity.isEmpty() || entity.startsWith(":-") || entity.startsWith("--") || entity.equals("---")
                    || entity.contains("实体")) {
                continue;
            }
            out.add(new ParsedCoreEntity(category, entity, cells[1], cells[2]));
        }
        return out;
    }

    private static String categoryOf(String heading) {
        if (heading.contains("2.1")) {
            return "MASTER";
        }
        if (heading.contains("2.2")) {
            return "TRANSACTIONAL";
        }
        if (heading.contains("2.3")) {
            return "CONFIG";
        }
        return null;
    }

    /** split a `| a | b | c |` markdown row into trimmed cells [a, b, c]. The surrounding pipes
     *  are stripped first because {@link String#split} drops trailing empty tokens. */
    private static String[] splitRow(String line) {
        String s = line;
        if (s.startsWith("|")) {
            s = s.substring(1);
        }
        if (s.endsWith("|")) {
            s = s.substring(0, s.length() - 1);
        }
        String[] raw = s.split("\\|");
        List<String> cells = new ArrayList<>();
        for (String part : raw) {
            cells.add(part.trim());
        }
        return cells.toArray(new String[0]);
    }
}
