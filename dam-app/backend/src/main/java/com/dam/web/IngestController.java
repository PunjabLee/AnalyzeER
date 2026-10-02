package com.dam.web;

import com.dam.ingest.DdlIngestionService;
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

    public IngestController(DdlIngestionService ingestionService,
                            ErModelLabelsIngestionService labelsService,
                            RelationIngestionService relationService) {
        this.ingestionService = ingestionService;
        this.labelsService = labelsService;
        this.relationService = relationService;
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

    /** POST /api/ingest/relations ; ingers FK[...] edges from 03-逻辑数据模型 (relation channel, M1.1) */
    @PostMapping("/relations")
    public RelationIngestionService.RelationReport ingestRelations(@RequestParam(required = false) String dir) {
        return relationService.ingest(dir);
    }
}
