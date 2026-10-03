package com.dam.parser;

import com.dam.parser.ParsedErRelation.Kind;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 通道①符号/方向/基数/列/证据解析回归（PLAN D1 根因守卫）。锁定评审整改：
 * <ul>
 *   <li>S1：关系符号左右标记决定多端与方向，M:N 不建、1:1 归为 ONE_TO_ONE（歧义，仅 enrich）；</li>
 *   <li>S1-3：符号与标签自述基数冲突（如 {@code ||--o{} 却写 {@code "N:N"}）降级 AMBIGUOUS、不可信；</li>
 *   <li>S2-1：单侧缺标记（{@code A ..o{ B}）归 UNSUPPORTED_SYMBOL（计数、不静默丢）；</li>
 *   <li>S2-2：父表 PK {@code id} 绝不作为子表 FK 候选；目标列仍允许 {@code id}。</li>
 * </ul>
 */
class ErDiagramRelationParserTest {

    private ParsedErRelation one(String line) {
        List<ParsedErRelation> r = ErDiagramRelationParser.parse(
                List.of("```mermaid", "erDiagram", line, "```"));
        assertEquals(1, r.size(), "应解析出恰好一条关系: " + line);
        return r.get(0);
    }

    @Test
    void oneToManyChildIsRight() {
        ParsedErRelation r = one("jf_customer ||--o{ jf_sales_order : \"1:N [命名推断] order.customer_id -> customer.id (NOT NULL)\"");
        assertEquals(Kind.ONE_TO_MANY, r.getKind());
        assertEquals("1:N", r.getCardinality());
        assertTrue(r.allowsNewEdge());
        assertEquals("customer_id", r.getFromColumnCandidates().get(0));
        assertEquals("id", r.getToColumnCandidate());          // 2 字符 id 作为目标列不被丢弃
        assertEquals("字段命名", r.getEvidenceLevel());
    }

    @Test
    void manyToOneChildIsLeft() {
        ParsedErRelation r = one("jf_business_inventory }o..|| jf_movement : \"[命名推断] mv.inventory_id\"");
        assertEquals(Kind.MANY_TO_ONE, r.getKind());
        assertEquals("1:N", r.getCardinality());
    }

    @Test
    void manyToManyIsSkippedKind() {
        ParsedErRelation r = one("jf_product_color_coordina_mapping }o--o{ jf_product : \"M:N [命名推断·编码] pccm.sku -> product.sku\"");
        assertEquals(Kind.MANY_TO_MANY, r.getKind());
        assertEquals("N:M", r.getCardinality());
    }

    @Test
    void oneToOneIsAmbiguousEnrichOnly() {
        ParsedErRelation r = one("jf_inventory_movement_idempotent ||..o| jf_wms_request_record : \"关联 [注释明示] imi.request_id(use的WMS请求号)=wrr.request_id\"");
        assertEquals(Kind.ONE_TO_ONE, r.getKind());
        assertEquals("1:1", r.getCardinality());
        assertFalse(r.canResolveProse());                       // 方向不可判：不得消解散文目标
        assertEquals("request_id", r.getToColumnCandidate());   // 以 = 作为目标分隔符
    }

    @Test
    void shortAliasIsCapturedAndEvidenceUpgraded() {
        ParsedErRelation r = one("jf_a ||--o{ jf_b : \"1:N [命名推断+索引] rs.customer_id -> b.id\"");
        assertEquals(Kind.ONE_TO_MANY, r.getKind());
        assertTrue(r.getFromColumnCandidates().contains("customer_id")); // 2 字母别名不再被门槛滤掉
        assertEquals("索引佐证", r.getEvidenceLevel());                  // 命名推断+索引 → 更强的索引佐证
    }

    @Test
    void labelCardinalityClashWithSymbolBecomesAmbiguous() {
        // 评审 S1-1 的真实假边：符号 ||--o{ (1:N) 却自述 "N:N"、多单号串 → 不可信 → AMBIGUOUS
        ParsedErRelation r = one("jf_collection_notice ||--o{ jf_invoice_application : \"N:N [命名推断·编码] app.collection_bill_codes(多单号串) -> 收款单 [跨域D04]\"");
        assertEquals(Kind.AMBIGUOUS, r.getKind());
        assertTrue(r.isUntrusted());
        assertNull(r.getCardinality());
    }

    @Test
    void malformedSymbolMissingLeftMarkerIsUnsupported() {
        // 评审 S2-1：作者漏写左标记 A ..o{ B，方向不可判，归 UNSUPPORTED_SYMBOL（计数而非静默丢）
        ParsedErRelation r = one("jf_report ..o{ jf_goods : \"弱关联 [语义推断] report.goods_code -> goods(无 id FK)\"");
        assertEquals(Kind.UNSUPPORTED_SYMBOL, r.getKind());
        assertTrue(r.isUntrusted());
        assertNull(r.getCardinality());
    }

    @Test
    void bareParentIdIsNeverAFkCandidate() {
        // 评审 S2-2：目标写法 parent.id 出现在分隔符前时，id 不得被当作子表 FK 候选
        ParsedErRelation r = one("jf_quality_compensation ||--o{ jf_x : \"1:N [命名推断] jf_personnel.id / lcap_user.id 双候选\"");
        assertFalse(r.getFromColumnCandidates().contains("id"), "裸 id 不应作为 FK 候选");
    }

    @Test
    void bareFkTokenWithoutAliasIsRecoveredAsCandidate() {
        // 评审 S2-4：列名无 alias. 前缀时兜底提取 *_id/*_code token
        ParsedErRelation r = one("jf_statement }o..|| jf_customer_reconciliation : \"customer_reconciliation_id 对账单到对账信息[注释明示]\"");
        assertEquals(Kind.MANY_TO_ONE, r.getKind());
        assertTrue(r.getFromColumnCandidates().contains("customer_reconciliation_id"));
    }

    @Test
    void fieldNamingBracketMapsToColumnNaming() {
        ParsedErRelation r = one("jf_a ||--o{ jf_b : \"[字段命名] b.a_id\"");
        assertEquals("字段命名", r.getEvidenceLevel());
    }

    @Test
    void entityDefinitionLineIsIgnored() {
        assertTrue(ErDiagramRelationParser.parse(
                List.of("```mermaid", "jf_sales_order { bigint id PK \"主键\" }", "```")).isEmpty());
    }

    @Test
    void labelWithoutSeparatorHasNoTargetColumn() {
        assertNull(one("jf_a ||--o{ jf_b : \"1:N [命名推断] b.a_id\"").getToColumnCandidate());
    }
}
