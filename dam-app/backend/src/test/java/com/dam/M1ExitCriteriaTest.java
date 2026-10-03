package com.dam;

import com.dam.domain.MetaAsset;
import com.dam.export.ExportService;
import com.dam.repository.MetaAssetRepository;
import com.dam.repository.MetaDomainRepository;
import com.dam.repository.MetaRelationRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M1 exit-criteria smoke (PLAN §6 M1): after the chained startup ingestion
 * (DDL -> er-model labels -> relations) the catalog must hold the full census,
 * the exact A/B/C grading split and the inferred edge set, and the export must
 * be re-loadable and diff-able by asset_urn.
 *
 * <p>Requires test_erp.sql + er-model/ to be resolvable by the source locators.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class M1ExitCriteriaTest {

    @Autowired
    MetaAssetRepository assetRepo;
    @Autowired
    MetaDomainRepository domainRepo;
    @Autowired
    MetaRelationRepository relRepo;
    @Autowired
    ExportService exportService;

    @Test
    void fullCensusAndGradingSplit() {
        assertEquals(1322, assetRepo.count(), "total tables ingested from DDL");
        Map<String, Long> byGrading = assetRepo.findAll().stream()
                .collect(Collectors.groupingBy(MetaAsset::getGrading, Collectors.counting()));
        assertEquals(349L, byGrading.get("A"), "A-grade = 332 jf_ + 17 OT");
        assertEquals(853L, byGrading.get("B"), "B-grade = lcap_ + quartz + activiti");
        assertEquals(120L, byGrading.get("C"), "C-grade = dated bak + test_ copies");
    }

    @Test
    void domainsAndRelationsIngested() {
        // 18 business domains + OT + B/C pseudo domains
        assertTrue(domainRepo.count() >= 19, "meta_domain rebuilt from 00-总览");
        // 修复 B-1（错挂/丢边/重复）后重出的【通道②(逻辑FK列)】边集基准：413 条（含多态 FK[A/B] 展开）。
        // M3 通道①(ER证据摘录)会在其上叠加目录内真实新边（总数变大），但 413 的 M1 基准口径只锚定②，
        // 完整性由 M1RelationIntegrityTest / ErEvidenceIntegrityTest 的"源表必含该列"强不变式分别守卫。
        assertEquals(413L, relRepo.findByOrigin("逻辑FK列").size(),
                "FK[...] edges parsed from 03-逻辑数据模型 (channel-2 baseline)");
        assertTrue(relRepo.count() >= 413L, "total edges (② plus ER overlay) at least the ② baseline");
    }

    @Test
    void exportIsDiffableByUrn() throws Exception {
        String json = exportService.exportJson(null, null);
        var root = new com.fasterxml.jackson.databind.ObjectMapper().readTree(json);
        assertEquals(ExportService.SCHEMA, root.get("schema").asText());
        assertEquals(1322, root.get("assetCount").asInt());
        // full edge set exported (incl. unresolved). Channel-2 (逻辑FK列) portion must equal the
        // accepted 413 baseline; the total is >= 413 because channel-1 ER evidence overlays more edges.
        long exportFk = 0;
        for (var n : root.get("relations")) {
            if ("逻辑FK列".equals(n.get("origin").asText())) {
                exportFk++;
            }
        }
        assertEquals(413L, exportFk, "channel-2 (逻辑FK列) edges in export");
        assertTrue(root.get("relations").size() >= 413, "full edge set exported (incl. ER overlay)");
        // every asset carries its urn anchor (diff key)
        root.get("assets").forEach(n -> assertTrue(n.get("urn").asText().startsWith("mysql:test_erp:")));

        String yaml = exportService.exportYaml("D01", null);
        assertTrue(yaml.contains(ExportService.SCHEMA), "yaml export carries schema tag");
        assertTrue(yaml.contains("jf_sales_order"), "domain slice export contains D01 tables");
    }
}
