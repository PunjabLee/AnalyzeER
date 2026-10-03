package com.dam;

import com.dam.domain.MetaAsset;
import com.dam.domain.MetaColumn;
import com.dam.domain.MetaRelation;
import com.dam.repository.MetaAssetRepository;
import com.dam.repository.MetaColumnRepository;
import com.dam.repository.MetaRelationRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M3 通道①(ER证据摘录)强不变式守卫 (PLAN D1：新增通道须配套同款强不变式)。
 *
 * <p>镜像 M1 B-1：每条 origin=ER证据摘录 的边，其子表(from)列清单必须真实包含 fromColumn，
 * 且 to 端资产真实存在——杜绝臆造端点/错挂幻影边。同时校验基数枚举合法、ER 优先叠加确有效果
 * (1:1 基数、注释明示证据由 ER 通道带入)。通道②的 413 基准另由 {@link M1RelationIntegrityTest} 守。
 */
@SpringBootTest
class ErEvidenceIntegrityTest {

    @Autowired
    MetaRelationRepository relationRepo;
    @Autowired
    MetaAssetRepository assetRepo;
    @Autowired
    MetaColumnRepository columnRepo;

    private static final Set<String> VALID_CARDINALITY = Set.of("1:1", "1:N");
    private static final Set<String> VALID_EVIDENCE =
            Set.of("注释明示", "索引佐证", "字段命名", "业务语义推断", "待确认");

    @Test
    void everyErEdgeResolvesBothEndpointsAndChildOwnsFkColumn() {
        List<MetaRelation> erEdges = relationRepo.findByOrigin("ER证据摘录");
        assertFalse(erEdges.isEmpty(), "通道①应至少叠加出若干 ER 证据边");

        Map<Long, String> nameById = assetRepo.findAll().stream()
                .collect(Collectors.toMap(MetaAsset::getId, MetaAsset::getName));
        Map<Long, Set<String>> colsByAsset = new HashMap<>();

        List<String> violations = erEdges.stream().filter(r -> {
            Set<String> cols = colsByAsset.computeIfAbsent(r.getFromAssetId(), id ->
                    columnRepo.findByAssetIdOrderByOrdinalAsc(id).stream().map(MetaColumn::getName)
                            .map(n -> n.toLowerCase(Locale.ROOT)).collect(Collectors.toSet()));
            return r.getFromColumn() == null || !cols.contains(r.getFromColumn().toLowerCase(Locale.ROOT));
        }).map(r -> String.format("%s.%s -> %s (子表不含该FK列)",
                nameById.getOrDefault(r.getFromAssetId(), "?" + r.getFromAssetId()),
                r.getFromColumn(), nameById.getOrDefault(r.getToAssetId(), "?")))
                .collect(Collectors.toList());
        assertTrue(violations.isEmpty(), "发现 " + violations.size()
                + " 条 ER 幻影/错挂边：\n" + String.join("\n", violations.stream().limit(20).toList()));

        // to 端资产必须真实存在(不允许臆造目标)——ER 通道只连目录内真实表
        long danglingTo = erEdges.stream().filter(r -> r.getToAssetId() == null).count();
        assertEquals(0, danglingTo, "ER 通道不得产出 to 端缺失的边");

        for (MetaRelation r : erEdges) {
            assertNotNull(r.getCardinality(), "ER 边必须带基数: " + r.getFromColumn());
            assertTrue(VALID_CARDINALITY.contains(r.getCardinality()),
                    "非法基数枚举: " + r.getCardinality());
            assertTrue(VALID_EVIDENCE.contains(r.getEvidenceLevel()),
                    "非法证据枚举: " + r.getEvidenceLevel());
        }
    }

    @Test
    void overlayFillsCardinalityAndCarriesStrongestEvidence() {
        Map<String, Long> idByName = assetRepo.findAll().stream()
                .collect(Collectors.toMap(a -> a.getName().toLowerCase(Locale.ROOT), MetaAsset::getId));

        // jf_sales_order_cus 1:1 关系（ER 通道独有的基数），叠加后必有一张边带 1:1
        MetaRelation oneToOne = relationRepo.findByFromAssetId(idByName.get("jf_sales_order_cus")).stream()
                .filter(r -> "sales_order_id".equalsIgnoreCase(r.getFromColumn())
                        && r.getToAssetId() != null && r.getToAssetId().equals(idByName.get("jf_sales_order")))
                .findFirst().orElse(null);
        assertNotNull(oneToOne, "jf_sales_order_cus.sales_order_id -> jf_sales_order 边应存在");
        assertEquals("1:1", oneToOne.getCardinality(), "ER 通道应带入 1:1 基数");

        // 全库至少存在一条 注释明示 证据边（ER 通道独有最强证据，来自 [注释明示] 标注）
        boolean hasExplicit = relationRepo.findByOrigin("ER证据摘录").stream()
                .anyMatch(r -> "注释明示".equals(r.getEvidenceLevel()));
        assertTrue(hasExplicit, "ER 通道应带入 注释明示 级别证据");
    }
}
