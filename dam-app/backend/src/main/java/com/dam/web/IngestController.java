package com.dam.web;

import com.dam.ingest.DdlIngestionService;
import com.dam.ingest.IngestReport;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/ingest")
public class IngestController {

    private final DdlIngestionService ingestionService;

    public IngestController(DdlIngestionService ingestionService) {
        this.ingestionService = ingestionService;
    }

    /** POST /api/ingest/ddl?path=<optional sql path> ; empty path -> resolve default test_erp.sql */
    @PostMapping("/ddl")
    public IngestReport ingestDdl(@RequestParam(required = false) String path) {
        return ingestionService.ingestPath(path);
    }
}
