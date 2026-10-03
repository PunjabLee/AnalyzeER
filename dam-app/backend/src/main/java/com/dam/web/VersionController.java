package com.dam.web;

import com.dam.audit.AuditService;
import com.dam.domain.MetaVersion;
import com.dam.repository.MetaVersionRepository;
import com.dam.version.VersionService;
import com.dam.version.VersionService.ChangedAsset;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Version snapshot + diff API (capability M4 version mgmt, PLAN §3.2-(4) / R7, adopted D3):
 * capture a snapshot of the catalog, list snapshots and drill into the per-asset / per-column
 * changes of any snapshot vs its predecessor. Reads are open; snapshotting is a write
 * (POST -> ADMIN|STEWARD via SecurityConfig) and is audited.
 */
@RestController
@RequestMapping("/api/versions")
public class VersionController {

    private final VersionService versionService;
    private final MetaVersionRepository versionRepo;
    private final AuditService audit;

    public VersionController(VersionService versionService,
                             MetaVersionRepository versionRepo,
                             AuditService audit) {
        this.versionService = versionService;
        this.versionRepo = versionRepo;
        this.audit = audit;
    }

    /** snapshot header + per-changeType summary */
    public record VersionView(Long id, Integer versionNo, String baselineType, Instant snapshotAt,
                              Integer assetCount, String note, Map<String, Long> changeSummary) { }

    public record SnapshotRequest(String note) { }

    @PostMapping("/snapshot")
    public ResponseEntity<VersionView> snapshot(@RequestBody(required = false) SnapshotRequest req) {
        String note = req == null ? null : req.note();
        MetaVersion v = versionService.createSnapshot(note);
        audit.record("VERSION_SNAPSHOT", "v" + v.getVersionNo(), v.getBaselineType());
        return ResponseEntity.ok(toView(v));
    }

    @GetMapping
    public List<VersionView> list() {
        return versionRepo.findAllByOrderByVersionNoDesc().stream().map(this::toView).toList();
    }

    @GetMapping("/{id}")
    public ResponseEntity<VersionView> get(@PathVariable Long id) {
        return versionRepo.findById(id).map(v -> ResponseEntity.ok(toView(v)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/{id}/changes")
    public ResponseEntity<List<ChangedAsset>> changes(@PathVariable Long id) {
        if (versionRepo.findById(id).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(versionService.changedAssets(id));
    }

    private VersionView toView(MetaVersion v) {
        return new VersionView(v.getId(), v.getVersionNo(), v.getBaselineType(), v.getSnapshotAt(),
                v.getAssetCount(), v.getNote(), versionService.summary(v.getId()));
    }
}
