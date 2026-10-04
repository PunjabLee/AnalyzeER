package com.dam;

import com.dam.domain.Candidates;
import com.dam.domain.Candidates.Candidate;
import com.dam.domain.MetaRelation;
import com.dam.ingest.ErEvidenceIngestionService;
import com.dam.ingest.RelationIngestionService;
import com.dam.repository.MetaAssetRepository;
import com.dam.repository.MetaRelationRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * C-1 remediation guards (PLAN §3.2 R3): multi-candidate targets are structured JSON on the edge
 * ({@code candidate_targets}), not prose squeezed into the 300-char {@code basis_raw}. Entries are
 * enumerable by the future workbench, immune to truncation, and every named candidate must be a
 * REAL catalog asset (R4: a candidate may name an alternative, never fabricate one).
 */
@SpringBootTest
class RelationCandidateTest {

    @Autowired
    RelationIngestionService relationService;
    @Autowired
    ErEvidenceIngestionService erEvidenceService;
    @Autowired
    MetaRelationRepository relRepo;
    @Autowired
    MetaAssetRepository assetRepo;

    @Test
    void polymorphicAbSiblingsCarryTheFullCandidateSet() {
        // the corpus' one resolvable FK[A/B]: D02 jf_sales_stock_review_modif_record.order_code
        //   FK[jf_reservation_stock/jf_stock_pot_replenishment·命名推断(多态)]
        List<MetaRelation> siblings = relRepo.findAll().stream()
                .filter(r -> "逻辑FK列".equals(r.getOrigin())
                        && "order_code".equalsIgnoreCase(r.getFromColumn())
                        && r.getSourceDoc() != null && r.getSourceDoc().contains("D02"))
                .filter(r -> Candidates.read(r.getCandidateTargets()).stream()
                        .anyMatch(c -> "多态A/B".equals(c.why())))   // channel-2 polymorphic only
                .toList();
        assertEquals(2, siblings.size(), "polymorphic cell must expand to its 2 sibling edges (M1 baseline shape)");

        Set<String> expected = Set.of("jf_reservation_stock", "jf_stock_pot_replenishment");
        for (MetaRelation e : siblings) {
            List<Candidate> cs = Candidates.read(e.getCandidateTargets());
            assertEquals(2, cs.size(), "each sibling edge carries the whole candidate set");
            assertEquals(expected, cs.stream().map(Candidate::target).collect(Collectors.toSet()));
            assertTrue(cs.stream().allMatch(c -> "逻辑FK列".equals(c.source()) && "多态A/B".equals(c.why())));
            // R3/R4: candidates name real assets and the verdict stays human-owned
            assertTrue(cs.stream().allMatch(c -> assetRepo.findByNameIgnoreCase(c.target()).isPresent()));
            assertEquals("待确认", e.getConfirmStatus());
        }
        // the two edges themselves point at different resolved targets (sibling, not duplicate)
        assertEquals(2, siblings.stream().map(MetaRelation::getToAssetId).distinct().count());
    }

    @Test
    void deferredErCandidatesAreQueryableAndReal() {
        List<MetaRelation> withE = relRepo.findAll().stream()
                .filter(r -> Candidates.read(r.getCandidateTargets()).stream()
                        .anyMatch(c -> ErEvidenceIngestionService.ORIGIN_ER.equals(c.source())))
                .toList();
        assertFalse(withE.isEmpty(), "channel-1 deferred candidates (multi-parent/self-denial) must be recorded");
        Set<String> legalWhy = Set.of("多父候选", "自证否认", "目标冲突");
        for (MetaRelation r : withE) {
            for (Candidate c : Candidates.read(r.getCandidateTargets())) {
                if (ErEvidenceIngestionService.ORIGIN_ER.equals(c.source())) {
                    // never fabricated: the ER parent is a real catalog table
                    assertTrue(assetRepo.findByNameIgnoreCase(c.target()).isPresent(),
                            "candidate not in catalog: " + c.target());
                    // each entry names WHY it is only a candidate, for workbench triage
                    assertTrue(legalWhy.contains(c.why()), "unexpected why: " + c.why());
                }
            }
        }
    }

    @Test
    void reingestChainKeepsCandidateListsStable() {
        Function<MetaRelation, Set<Candidate>> snapshot = r ->
                new HashSet<>(Candidates.read(r.getCandidateTargets()));
        Map<Long, Set<Candidate>> before = relRepo.findAll().stream()
                .filter(r -> r.getCandidateTargets() != null)
                .collect(Collectors.toMap(MetaRelation::getId, snapshot));
        assertFalse(before.isEmpty());

        relationService.ingest(null);
        erEvidenceService.ingest(null);

        Map<Long, Set<Candidate>> after = relRepo.findAll().stream()
                .filter(r -> r.getCandidateTargets() != null)
                .collect(Collectors.toMap(MetaRelation::getId, snapshot));
        assertEquals(before, after, "a ②+① re-run must neither duplicate nor drop candidate entries");
    }
}
