package com.dam.web;

import com.dam.domain.MetaAsset;
import com.dam.lineage.LineageService;
import com.dam.lineage.LineageService.Direction;
import com.dam.repository.MetaAssetRepository;
import com.dam.web.dto.Dtos.LineageView;
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

    public LineageController(MetaAssetRepository assetRepo, LineageService lineage) {
        this.assetRepo = assetRepo;
        this.lineage = lineage;
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
}
