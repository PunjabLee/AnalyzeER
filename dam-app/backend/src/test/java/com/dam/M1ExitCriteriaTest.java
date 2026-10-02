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
        // 406 parsed FK marks, polymorphic targets expanded -> 408 stored edges
        assertEquals(408L, relRepo.count(), "FK[...] edges parsed from 03-逻辑数据模型");
    }

    @Test
    void exportIsDiffableByUrn() throws Exception {
        String json = exportService.exportJson(null, null);
        var root = new com.fasterxml.jackson.databind.ObjectMapper().readTree(json);
        assertEquals(ExportService.SCHEMA, root.get("schema").asText());
        assertEquals(1322, root.get("assetCount").asInt());
        assertEquals(408, root.get("relations").size(), "full edge set exported (incl. unresolved)");
        // every asset carries its urn anchor (diff key)
        root.get("assets").forEach(n -> assertTrue(n.get("urn").asText().startsWith("mysql:test_erp:")));

        String yaml = exportService.exportYaml("D01", null);
        assertTrue(yaml.contains(ExportService.SCHEMA), "yaml export carries schema tag");
        assertTrue(yaml.contains("jf_sales_order"), "domain slice export contains D01 tables");
    }
}
