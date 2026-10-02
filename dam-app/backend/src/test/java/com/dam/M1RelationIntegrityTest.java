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
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M1 退出标准强守卫：关系通道不得产出"错挂/幻影"边。
 *
 * <p>不变式——每条关系的源表(source asset)列清单必须真实包含其 fromColumn。
 * 修复前的解析器在遇到带脚注(†/‡)或多表标题时会把边静默挂到上一张表，
 * 产生源表根本不含该列的假边；本测试正是拦截此类回归的守卫。
 * 同时对关系总数基准(413)做精确断言，防止少摄/多摄无人察觉。
 */
@SpringBootTest
class M1RelationIntegrityTest {

    @Autowired
    MetaRelationRepository relationRepo;
    @Autowired
    MetaAssetRepository assetRepo;
    @Autowired
    MetaColumnRepository columnRepo;

    @Test
    void everyEdgeSourceTableActuallyContainsItsColumn() {
        List<MetaRelation> relations = relationRepo.findAll();
        // 基准：修复错挂/丢边/重复后重出的边集大小
        assertEquals(413, relations.size(), "relation edge baseline after B-1 fix");

        // 缓存每个 asset 的列名集合(小写)避免逐边重复查库
        Map<Long, Set<String>> columnsByAsset = new HashMap<>();
        Map<Long, String> nameById = assetRepo.findAll().stream()
                .collect(Collectors.toMap(MetaAsset::getId, MetaAsset::getName));

        List<String> violations = relations.stream()
                .filter(r -> {
                    Set<String> cols = columnsByAsset.computeIfAbsent(r.getFromAssetId(), id ->
                            columnRepo.findByAssetIdOrderByOrdinalAsc(id).stream()
                                    .map(MetaColumn::getName)
                                    .map(n -> n.toLowerCase(java.util.Locale.ROOT))
                                    .collect(Collectors.toSet()));
                    return r.getFromColumn() == null
                            || !cols.contains(r.getFromColumn().toLowerCase(java.util.Locale.ROOT));
                })
                .map(r -> String.format("%s.%s -> %s (源表不含该列)",
                        nameById.getOrDefault(r.getFromAssetId(), "?" + r.getFromAssetId()),
                        r.getFromColumn(), r.getTargetRaw()))
                .collect(Collectors.toList());

        assertTrue(violations.isEmpty(),
                "发现 " + violations.size() + " 条错挂/幻影边：\n"
                        + String.join("\n", violations.stream().limit(30).collect(Collectors.toList())));
    }

    @Test
    void noDuplicateEdges() {
        List<MetaRelation> relations = relationRepo.findAll();
        // A polymorphic FK[A/B] legitimately yields TWO rows that share (from, column, targetRaw)
        // but resolve to DIFFERENT toAssetId (A and B) — so the de-dup key must include toAssetId,
        // falling back to targetRaw only for unresolved targets.
        long distinct = relations.stream()
                .map(r -> r.getFromAssetId() + "\u0001" + r.getFromColumn() + "\u0001"
                        + (r.getToAssetId() != null ? r.getToAssetId() : "raw:" + r.getTargetRaw()))
                .distinct().count();
        assertEquals(relations.size(), distinct, "存在重复边 (from,column,resolved-target)");
    }
}
