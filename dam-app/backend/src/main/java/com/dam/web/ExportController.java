package com.dam.web;

import com.dam.export.ExportService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Metadata export endpoints (PLAN M1.4): download the catalog snapshot as JSON/YAML,
 * optionally sliced by domain/grading for reviewable diffs.
 */
@RestController
@RequestMapping("/api/export")
public class ExportController {

    private final ExportService exportService;

    public ExportController(ExportService exportService) {
        this.exportService = exportService;
    }

    @GetMapping(value = "/json", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> json(@RequestParam(required = false) String domain,
                                       @RequestParam(required = false) String grading) {
        return ResponseEntity.ok(exportService.exportJson(domain, grading));
    }

    @GetMapping(value = "/yaml", produces = "application/yaml")
    public ResponseEntity<String> yaml(@RequestParam(required = false) String domain,
                                       @RequestParam(required = false) String grading) {
        return ResponseEntity.ok(exportService.exportYaml(domain, grading));
    }
}
