package com.dam.parser;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * One relation edge extracted from a 03-逻辑数据模型 table section, e.g.
 * {@code | customer_id | bigint | N | | FK[jf_customer·命名推断] | 客户id |}.
 */
public class ParsedRelation {

    private final String fromTable;
    private final String fromColumn;
    /** raw first segment inside FK[...] (target table text, may be prose or A/B polymorphic) */
    private final String targetRaw;
    /** raw second segment (依据), e.g. 命名推断+索引佐证 / **注释明示** / 语义推断·待确认 */
    private final String basisRaw;
    /** resolved target table names (empty when prose-only) */
    private final List<String> targets = new ArrayList<>();
    /** optional target column hint from (order_no)/(code) etc., null if absent */
    private String targetColumn;

    public ParsedRelation(String fromTable, String fromColumn, String targetRaw, String basisRaw) {
        this.fromTable = fromTable;
        this.fromColumn = fromColumn;
        this.targetRaw = targetRaw;
        this.basisRaw = basisRaw;
    }

    public String getFromTable() { return fromTable; }
    public String getFromColumn() { return fromColumn; }
    public String getTargetRaw() { return targetRaw; }
    public String getBasisRaw() { return basisRaw; }
    public List<String> getTargets() { return targets; }
    public String getTargetColumn() { return targetColumn; }
    public void setTargetColumn(String targetColumn) { this.targetColumn = targetColumn; }

    /**
     * Evidence level per the five-level ladder (er-model 00-总览 §六), strongest signal wins.
     */
    public String evidenceLevel() {
        String b = basisRaw == null ? "" : basisRaw;
        if (b.contains("注释明示")) {
            return "注释明示";
        }
        if (b.contains("索引佐证")) {
            return "索引佐证";
        }
        if (b.contains("命名推断") || b.contains("自关联")) {
            return "字段命名";
        }
        if (b.contains("语义推断")) {
            return "业务语义推断";
        }
        return "待确认";
    }

    public double confidence() {
        return switch (evidenceLevel()) {
            case "注释明示" -> 0.8;
            case "索引佐证" -> 0.7;
            case "字段命名" -> 0.6;
            case "业务语义推断" -> 0.4;
            default -> 0.3;
        };
    }

    /**
     * Splits the raw target segment into resolvable table names.
     * Handles: 自关联 (self), polymorphic "A/B" lists, prose targets (kept unresolved).
     */
    public void resolveTargets(String selfTable, Set<String> knownTablesLower) {
        String t = targetRaw == null ? "" : targetRaw.replace("*", "").trim();
        if (t.isEmpty()) {
            return;
        }
        if (t.contains("自关联") || t.equalsIgnoreCase(selfTable)) {
            targets.add(selfTable);
            return;
        }
        // slash-separated candidate list, e.g. jf_reservation_stock/jf_stock_pot_replenishment
        for (String part : t.split("/")) {
            String cand = part.trim();
            // prose fragments like "产品/细码" or "库存明细" won't match any table -> ignored here,
            // the unresolved raw text is preserved on the edge itself
            if (knownTablesLower.contains(cand.toLowerCase())) {
                targets.add(cand);
            }
        }
    }
}
