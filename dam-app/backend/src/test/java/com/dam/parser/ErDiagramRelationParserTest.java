package com.dam.parser;

import com.dam.parser.ParsedErRelation.Kind;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 通道①符号/方向/列/证据解析回归（PLAN D1 根因守卫）。锁定 S1 修复：
 * 关系符号左右标记决定多端与方向，M:N 不建、1:1 归为 ONE_TO_ONE（歧义，仅 enrich）；
 * 别名/目标列去长度门槛（2 字母别名、2 字符 id 均可解析），目标分隔符含 ->/=/↔。
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
        assertEquals("id", r.getToColumnCandidate());          // 2 字符 id 不再被丢弃
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
        assertEquals("request_id", r.getToColumnCandidate());   // 以 = 作为目标分隔符
    }

    @Test
    void shortAliasIsCapturedAndEvidenceUpgraded() {
        ParsedErRelation r = one("jf_a ||--o{ jf_b : \"N:1 [命名推断+索引] rs.customer_id\"");
        assertEquals(Kind.ONE_TO_MANY, r.getKind());
        assertTrue(r.getFromColumnCandidates().contains("customer_id")); // 2 字母别名不再被门槛滤掉
        assertEquals("索引佐证", r.getEvidenceLevel());                  // 命名推断+索引 → 更强的索引佐证
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
