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
     * POST /api/ingest/relations ; rebuilds FK[...] edges from 03-逻辑数据模型 (channel-2) and then
     * re-overlays ER evidence (channel-1). Channel-2 does a full delete+rebuild, so the overlay must
     * follow here too — otherwise a manual channel-2 re-run would silently drop the cardinality and
     * all ER证据摘录 edges until the next restart.
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
     * store WITHOUT rebuilding channel-2. Unlike /relations (whose channel-2 stage does a full
     * delete+rebuild that resets every confirm_status to 待确认), this is safe to call after human
     * confirmation work: it re-enriches cardinality/evidence idempotently and preserves 已确认/驳回.
     */
    @PostMapping("/er-evidence")
    public ErEvidenceIngestionService.ErReport ingestErEvidence(@RequestParam(required = false) String dir) {
        return erEvidenceService.ingest(dir);
    }
}
