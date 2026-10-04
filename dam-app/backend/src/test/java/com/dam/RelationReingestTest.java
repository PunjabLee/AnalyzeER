package com.dam;

import com.dam.domain.MetaAsset;
import com.dam.domain.MetaRelation;
import com.dam.ingest.ErEvidenceIngestionService;
import com.dam.ingest.RelationIngestionService;
import com.dam.repository.MetaAssetRepository;
import com.dam.repository.MetaRelationRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * C-2 remediation guards: channel-2 re-ingestion is an INCREMENTAL upsert, not the old
 * delete-all rebuild. Human verdicts (已确认/驳回), channel-1 edges and manual edges must
 * survive any ②/①+② re-run; ghost ② rows must still be swept as stale.
 *
 * <p>Runs against the H2 profile whose startup auto-ingest already materialised the accepted
 * baseline: 413 逻辑FK列 + 74 ER证据摘录 = 487 edges.
 */
@SpringBootTest
class RelationReingestTest {

    @Autowired
    RelationIngestionService relationService;
    @Autowired
    ErEvidenceIngestionService erEvidenceService;
    @Autowired
    MetaRelationRepository relRepo;
    @Autowired
    MetaAssetRepository assetRepo;

    private long countByOrigin(String origin) {
        return relRepo.findAll().stream().filter(r -> origin.equals(r.getOrigin())).count();
    }

    @Test
    void channel2ReingestIsIdempotentAndKeepsTheAcceptedBaseline() {
        long totalBefore = relRepo.count();
        long erBefore = countByOrigin("ER证据摘录");

        RelationIngestionService.RelationReport report = relationService.ingest(null);

        // same documents => every row matches by identity: nothing new, nothing stale
        assertEquals(0, report.created(), "re-parse of unchanged docs must not create rows");
        assertEquals(0, report.removed(), "re-parse of unchanged docs must not remove rows");
        assertEquals(413, report.reused(), "all 413 channel-2 rows should be reused in place");
        assertEquals(413, countByOrigin("逻辑FK列"), "channel-2 baseline drifted after re-ingest");
        assertEquals(erBefore, countByOrigin("ER证据摘录"), "channel-1 edges must not be touched by channel-2");
        assertEquals(totalBefore, relRepo.count(), "total edge store size changed on an idempotent re-run");
    }

    @Test
    void humanVerdictsSurviveTheFullRelationsChain() {
        MetaRelation ch2 = relRepo.findAll().stream()
                .filter(r -> "逻辑FK列".equals(r.getOrigin()) && r.getToAssetId() != null)
                .findFirst().orElseThrow();
        MetaRelation ch1 = relRepo.findAll().stream()
                .filter(r -> "ER证据摘录".equals(r.getOrigin()))
                .findFirst().orElseThrow();
        ch2.setConfirmStatus("已确认");
        ch1.setConfirmStatus("驳回");
        relRepo.save(ch2);
        relRepo.save(ch1);

        // exactly what POST /api/ingest/relations does: ② upsert then ① overlay
        relationService.ingest(null);
        erEvidenceService.ingest(null);

        assertEquals("已确认", relRepo.findById(ch2.getId()).orElseThrow().getConfirmStatus(),
                "an accepted edge must not be reset to 待确认 by re-ingestion");
        assertEquals("驳回", relRepo.findById(ch1.getId()).orElseThrow().getConfirmStatus(),
                "a rejected edge must keep its verdict across the chain");
    }

    @Test
    void staleChannel2RowIsSweptWhileManualEdgesSurvive() {
        MetaAsset hub = assetRepo.findByNameIgnoreCase("jf_sales_order").orElseThrow();
        MetaAsset parent = assetRepo.findByNameIgnoreCase("jf_customer").orElseThrow();

        MetaRelation ghost = new MetaRelation();   // a ② row no document produces
        ghost.setFromAssetId(hub.getId());
        ghost.setFromColumn("zz_ghost_fk_col");
        ghost.setToAssetId(parent.getId());
        ghost.setTargetRaw("jf_customer·幽灵边");
        ghost.setEvidenceLevel("待确认");
        ghost.setOrigin("逻辑FK列");
        ghost.setConfidence(0.1);

        MetaRelation manual = new MetaRelation();  // a hand-drawn edge outside both channels
        manual.setFromAssetId(hub.getId());
        manual.setFromColumn("zz_manual_fk_col");
        manual.setToAssetId(parent.getId());
        manual.setTargetRaw("jf_customer·手工");
        manual.setEvidenceLevel("注释明示");
        manual.setOrigin("手工录入");
        manual.setConfidence(1.0);
        manual.setConfirmStatus("已确认");

        relRepo.saveAll(List.of(ghost, manual));
        try {
            relationService.ingest(null);

            assertTrue(relRepo.findById(ghost.getId()).isEmpty(),
                    "stale channel-2 rows must be removed by the upsert sweep");
            MetaRelation kept = relRepo.findById(manual.getId()).orElseThrow();
            assertEquals("手工录入", kept.getOrigin());
            assertEquals("已确认", kept.getConfirmStatus(), "manual verdicts are outside channel-2 scope");
        } finally {
            relRepo.deleteById(manual.getId());   // leave the shared store pristine for other tests
        }
    }

    @Test
    void danglingEdgesFromAnAssetRebuildAreSwept() {
        // M-5 smoke finding: POST /ingest/ddl re-numbers asset ids and strands OLD edges of any
        // origin — endpoints no longer resolving in the catalog are garbage and must be swept.
        MetaRelation orphan = new MetaRelation();
        orphan.setFromAssetId(900_001L);                 // ids outside the catalog by construction
        orphan.setFromColumn("sales_order_id");
        orphan.setToAssetId(900_002L);
        orphan.setTargetRaw("zz_gone_parent");
        orphan.setEvidenceLevel("字段命名");
        orphan.setOrigin("ER证据摘录");                    // channel-1 shape: sweep is origin-agnostic
        orphan.setConfidence(0.6);
        relRepo.save(orphan);

        long erBefore = countByOrigin("ER证据摘录");       // includes the orphan
        long totalBefore = relRepo.count();

        RelationIngestionService.RelationReport report = relationService.ingest(null);

        assertEquals(1, report.danglingRemoved(), "exactly the one stranded edge is reported");
        assertTrue(relRepo.findById(orphan.getId()).isEmpty(), "dangling edge must not survive");
        assertEquals(erBefore - 1, countByOrigin("ER证据摘录"), "live ER edges stay untouched");
        assertEquals(413, countByOrigin("逻辑FK列"), "channel-2 baseline unchanged by the sweep");
        assertEquals(totalBefore - 1, relRepo.count());
    }

    /** Review N-3: vanishing evidence must QUARANTINE a human verdict, never annihilate it. */
    @Test
    void confirmedStaleRowIsQuarantinedNotDeleted() {
        MetaAsset hub = assetRepo.findByNameIgnoreCase("jf_sales_order").orElseThrow();
        MetaAsset parent = assetRepo.findByNameIgnoreCase("jf_customer").orElseThrow();

        MetaRelation ghost = new MetaRelation();
        ghost.setFromAssetId(hub.getId());
        ghost.setFromColumn("zz_ghost_confirmed_col");
        ghost.setToAssetId(parent.getId());
        ghost.setTargetRaw("jf_customer·幽灵已确认");
        ghost.setEvidenceLevel("待确认");
        ghost.setOrigin("逻辑FK列");
        ghost.setConfidence(0.1);
        ghost.setConfirmStatus("已确认");          // a human decision on a now-vanished doc row
        relRepo.save(ghost);

        try {
            RelationIngestionService.RelationReport report = relationService.ingest(null);

            assertEquals(1, report.staleKept(), "the confirmed vanished-evidence row is quarantined");
            MetaRelation kept = relRepo.findById(ghost.getId()).orElseThrow();
            assertEquals("已确认", kept.getConfirmStatus(), "quarantine must not reset the verdict");
            assertTrue(kept.isConflictFlag(), "quarantine raises conflict_flag");
            assertTrue(kept.getIngestTrace() != null && kept.getIngestTrace().contains("文档证据消失待复核"),
                    "quarantine leaves a re-review note in ingest_trace (N-4: not in basis_raw)");
            assertTrue(kept.getBasisRaw() == null || !kept.getBasisRaw().contains("待复核"),
                    "basis_raw stays pure document text");
            assertEquals(0, report.danglingKept());
        } finally {
            relRepo.deleteById(ghost.getId());
        }
    }

    /** Review N-3: polymorphic FK[A/B] siblings keep their exact id→target mapping every run. */
    @Test
    void polymorphicSiblingTargetMappingIsStableAcrossReingest() {
        Map<Long, Long> mappingBefore = new HashMap<>();
        relRepo.findAll().stream()
                .filter(r -> "逻辑FK列".equals(r.getOrigin()) && r.getToAssetId() != null)
                .forEach(r -> mappingBefore.put(r.getId(), r.getToAssetId()));
        assertTrue(mappingBefore.size() > 300, "sanity: pinned-target baseline present (364 in corpus)");

        relationService.ingest(null);

        Map<Long, Long> mappingAfter = new HashMap<>();
        relRepo.findAll().stream()
                .filter(r -> "逻辑FK列".equals(r.getOrigin()) && r.getToAssetId() != null)
                .forEach(r -> mappingAfter.put(r.getId(), r.getToAssetId()));
        assertEquals(mappingBefore, mappingAfter,
                "re-ingest must not reassign any channel-2 row's target (no sibling cross-match)");
    }
}
