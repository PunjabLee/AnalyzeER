package com.dam.web;

import com.dam.ingest.DdlIngestionService;
import com.dam.ingest.ErEvidenceIngestionService;
import com.dam.ingest.ErModelLabelsIngestionService;
import com.dam.ingest.IngestReport;
import com.dam.ingest.RelationIngestionService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/ingest")
public class IngestController {

    private final DdlIngestionService ingestionService;
    private final ErModelLabelsIngestionService labelsService;
    private final RelationIngestionService relationService;
    private final ErEvidenceIngestionService erEvidenceService;

    public IngestController(DdlIngestionService ingestionService,
                            ErModelLabelsIngestionService labelsService,
                            RelationIngestionService relationService,
                            ErEvidenceIngestionService erEvidenceService) {
        this.ingestionService = ingestionService;
        this.labelsService = labelsService;
        this.relationService = relationService;
        this.erEvidenceService = erEvidenceService;
    }

    /** POST /api/ingest/ddl?path=<optional sql path> ; empty path -> resolve default test_erp.sql */
    @PostMapping("/ddl")
    public IngestReport ingestDdl(@RequestParam(required = false) String path) {
        return ingestionService.ingestPath(path);
    }

    /** POST /api/ingest/er-model-labels ; assigns A/B/C grading + domain codes from 00-总览 */
    @PostMapping("/er-model-labels")
    public ErModelLabelsIngestionService.LabelsReport ingestLabels(@RequestParam(required = false) String dir) {
        return labelsService.ingest(dir);
    }

    /**
     * POST /api/ingest/relations ; upserts FK[...] edges from 03-逻辑数据模型 (channel-2, incremental:
     * confirm_status/已确认/驳回 verdicts and all ER证据摘录 edges survive — C-2 remediation), sweeps
     * edges whose endpoints no longer resolve in the catalog (danglingRemoved — hygiene invariant
     * after an asset rebuild such as POST /ingest/ddl), and then re-overlays ER evidence
     * (channel-1). The overlay follows because channel-2 refreshes
     * document-derived facts (evidence/confidence/basis), so channel-1 must recompute its
     * upgrades, cardinality and conflict marks on top of the fresh base.
     */
    @PostMapping("/relations")
    public RelationStageResult ingestRelations(@RequestParam(required = false) String dir) {
        RelationIngestionService.RelationReport channel2 = relationService.ingest(dir);
        ErEvidenceIngestionService.ErReport channel1 = erEvidenceService.ingest(dir);
        return new RelationStageResult(channel2, channel1);
    }

    /** record of both relation channels: channel-2 (逻辑FK列) rebuild + channel-1 (ER证据) overlay. */
    public record RelationStageResult(RelationIngestionService.RelationReport channel2,
                                      ErEvidenceIngestionService.ErReport channel1) { }

    /**
     * POST /api/ingest/er-evidence ; re-overlays ER evidence (channel-1) onto the CURRENT relation
     * store WITHOUT touching channel-2. Since C-2 both this and /relations are safe to call after
     * human confirmation work: cardinality/evidence re-enrich idempotently and 已确认/驳回 verdicts
     * (and 驳回 positions) are preserved by both channels.
     */
    @PostMapping("/er-evidence")
    public ErEvidenceIngestionService.ErReport ingestErEvidence(@RequestParam(required = false) String dir) {
        return erEvidenceService.ingest(dir);
    }
}
