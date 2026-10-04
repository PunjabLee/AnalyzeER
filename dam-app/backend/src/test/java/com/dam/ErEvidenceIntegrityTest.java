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
import java.util.HashSet;
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

    @Test
    void noReversedSameColumnEdgePairsAndNoManyToManyEdge() {
        // S1 守卫：不得同时存在 (A.col -> B) 与 (B.col -> A)（同列名反向对 = 方向倒置假边的特征），
        // 且 M:N 关系从不被建成单方向边。
        List<MetaRelation> directed = relationRepo.findAll().stream()
                .filter(r -> r.getToAssetId() != null && r.getFromColumn() != null).toList();

        assertTrue(directed.stream().noneMatch(r -> "N:M".equals(r.getCardinality())),
                "M:N 关系不应被物化为单方向边");

        Map<Long, String> nameById = assetRepo.findAll().stream()
                .collect(Collectors.toMap(MetaAsset::getId, MetaAsset::getName));
        Set<String> forward = new HashSet<>();
        for (MetaRelation r : directed) {
            forward.add(r.getFromAssetId() + "\u0001" + r.getToAssetId() + "\u0001"
                    + r.getFromColumn().toLowerCase(Locale.ROOT));
        }
        List<String> reversed = directed.stream()
                .filter(r -> !r.getFromAssetId().equals(r.getToAssetId()))   // 排除合法自关联
                .filter(r -> forward.contains(r.getToAssetId() + "\u0001" + r.getFromAssetId() + "\u0001"
                        + r.getFromColumn().toLowerCase(Locale.ROOT)))
                .map(r -> String.format("%s.%s -> %s  (反向 %s.%s -> %s 同时存在)",
                        nameById.get(r.getFromAssetId()), r.getFromColumn(),
                        nameById.get(r.getToAssetId()), nameById.get(r.getToAssetId()),
                        r.getFromColumn(), nameById.get(r.getFromAssetId())))
                .distinct().toList();
        assertTrue(reversed.isEmpty(),
                "发现方向倒置的反向同列对（S1）：\n" + String.join("\n", reversed));
    }

    @Test
    void selfDenyingOrMultiParentCandidateNeverPinsTarget() {
        // S1-1/S1-2 守卫：被判定「自证否认」或「多父候选」的 ER 候选只写进 basis_raw（带『未消解』），
        // 绝不把②散文边的 to_asset_id 钉死——命中该注记的边必须仍未解析目标。
        List<MetaRelation> offenders = relationRepo.findAll().stream()
                .filter(r -> r.getBasisRaw() != null && r.getBasisRaw().contains("未消解"))
                .filter(r -> r.getToAssetId() != null)
                .toList();
        assertTrue(offenders.isEmpty(),
                "含『未消解』注记的边不得有具体 to_asset_id，实得 " + offenders.size() + " 条");
    }

    @Test
    void everyNewErEdgeIsDirectedOneToMany() {
        // 只有方向明确的 一↔多 才允许新建；1:1(歧义)/M:N/AMBIGUOUS 从不新建，
        // 故 origin=ER证据摘录 的新建边基数必恒为 1:N。
        List<MetaRelation> er = relationRepo.findByOrigin("ER证据摘录");
        assertFalse(er.isEmpty(), "通道①应至少新建若干边");
        List<MetaRelation> bad = er.stream()
                .filter(r -> !"1:N".equals(r.getCardinality())).toList();
        assertTrue(bad.isEmpty(), "ER 新建边应全为 1:N，异常 " + bad.size() + " 条");
    }

    @Test
    void overlayBringsCardinalityAndTargetColumnsAtScale() {
        // 通道①的价值主张（基数 + 目标列）必须有规模下限，否则正则/方向被改坏时 CI 无法察觉。
        List<MetaRelation> all = relationRepo.findAll();
        long withCardinality = all.stream().filter(r -> r.getCardinality() != null).count();
        // 交叉校验会保守地将符号↔标签基数冲突的行降级为 AMBIGUOUS（不写基数），故下限较前降。
        assertTrue(withCardinality >= 200, "ER 叠加应带入大量基数，实得=" + withCardinality);
        long withToCol = all.stream()
                .filter(r -> r.getCardinality() != null && r.getToColumn() != null).count();
        assertTrue(withToCol >= 25, "ER 的 ->/=/↔ 目标列应被大量补全，实得=" + withToCol);
        // [P1/N-8] 精确回归（原仅 >=425 下限）：退出标准数字入测试层——②413 + ①74 = 487。
        assertTrue(relationRepo.count() == 487, "总边恰为 ②413+①74=487，实得=" + relationRepo.count());
        long erEdges = all.stream().filter(r -> "ER证据摘录".equals(r.getOrigin())).count();
        assertTrue(erEdges == 74, "① ER 新边恰为 74，实得=" + erEdges);
    }
}
