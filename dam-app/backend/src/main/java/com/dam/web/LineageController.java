package com.dam.web;

import com.dam.domain.MetaAsset;
import com.dam.lineage.ImpactExportService;
import com.dam.lineage.ImpactExportService.ImpactReport;
import com.dam.lineage.LineageService;
import com.dam.lineage.LineageService.Direction;
import com.dam.repository.MetaAssetRepository;
import com.dam.web.dto.Dtos.LineageView;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Locale;
import java.util.Optional;

/**
 * M3-2 lineage query API (PLAN G3 / §5.2). Live recursive-CTE trace of a rooted table lineage subgraph;
 * the graph is not stored, so results always reflect the current {@code meta_relation}. Consumption by
 * the editable X6 canvas is a later M9.3 batch — this endpoint is the query backbone it will call.
 */
@RestController
@RequestMapping("/api/lineage")
public class LineageController {

    private final MetaAssetRepository assetRepo;
    private final LineageService lineage;
    private final ImpactExportService impactExport;

    public LineageController(MetaAssetRepository assetRepo, LineageService lineage,
                             ImpactExportService impactExport) {
        this.assetRepo = assetRepo;
        this.lineage = lineage;
        this.impactExport = impactExport;
    }

    /**
     * @param asset root table name (case-insensitive)
     * @param dir   {@code downstream} (impact: who references it) or {@code upstream} (source: what it references)
     * @param depth max hops, clamped server-side to {@code [1,10]}
     */
    @GetMapping
    public ResponseEntity<LineageView> query(@RequestParam String asset,
                                             @RequestParam(defaultValue = "downstream") String dir,
                                             @RequestParam(defaultValue = "6") int depth) {
        Optional<MetaAsset> root = assetRepo.findByNameIgnoreCase(asset.trim());
        if (root.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        Direction direction;
        try {
            direction = Direction.valueOf(dir.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException bad) {
            return ResponseEntity.badRequest().build();
        }
        MetaAsset a = root.get();
        return ResponseEntity.ok(lineage.trace(a.getName(), a.getId(), direction, depth));
    }

    /**
     * M3 exit criteria — flat change-impact list of the root's DOWNSTREAM subtree, downloadable.
     * One row per impacted table (root excluded) with the impact path and the propagating relation.
     *
     * @param format {@code csv} (Excel-ready, default) | {@code json} | {@code yaml}
     */
    @GetMapping("/impact")
    public ResponseEntity<String> impact(@RequestParam String asset,
                                         @RequestParam(defaultValue = "6") int depth,
                                         @RequestParam(defaultValue = "csv") String format) {
        Optional<MetaAsset> root = assetRepo.findByNameIgnoreCase(asset.trim());
        if (root.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        String fmt = format.trim().toLowerCase(Locale.ROOT);
        if (!fmt.equals("csv") && !fmt.equals("json") && !fmt.equals("yaml")) {
            return ResponseEntity.badRequest().build();
        }
        MetaAsset a = root.get();
        ImpactReport report = impactExport.build(a.getName(), depth);
        String body;
        MediaType media;
        switch (fmt) {
            case "json" -> {
                body = impactExport.toJson(report);
                media = MediaType.APPLICATION_JSON;
            }
            case "yaml" -> {
                body = impactExport.toYaml(report);
                media = MediaType.parseMediaType("application/yaml");
            }
            default -> {
                body = impactExport.toCsv(report);
                media = MediaType.parseMediaType("text/csv;charset=UTF-8");
            }
        }
        // filename derives from the resolved catalog name, never the raw request param
        String filename = "impact-" + a.getName().toLowerCase(Locale.ROOT) + "-d" + report.maxDepth() + "." + fmt;
        return ResponseEntity.ok()
                .contentType(media)
                .header("Content-Disposition", org.springframework.http.ContentDisposition.attachment()
                        .filename(filename).build().toString())
                .body(body);
    }
}
