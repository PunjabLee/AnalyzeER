package com.dam.web;

import com.dam.audit.AuditService;
import com.dam.domain.MetaAsset;
import com.dam.domain.MetaColumn;
import com.dam.domain.MetaRelation;
import com.dam.repository.MetaAssetRepository;
import com.dam.repository.MetaColumnRepository;
import com.dam.repository.MetaRelationRepository;
import com.dam.repository.SysUserRepository;
import com.dam.web.dto.Dtos.AssetDetail;
import com.dam.web.dto.Dtos.AssetSummary;
import com.dam.web.dto.Dtos.ColumnView;
import com.dam.web.dto.Dtos.ColumnOrder;
import com.dam.web.dto.Dtos.Facets;
import com.dam.web.dto.Dtos.GovernanceUpdate;
import com.dam.web.dto.Dtos.RelationView;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/assets")
public class AssetController {

    private final MetaAssetRepository assetRepo;
    private final MetaColumnRepository columnRepo;
    private final MetaRelationRepository relRepo;
    private final SysUserRepository userRepo;
    private final AuditService audit;

    public AssetController(MetaAssetRepository assetRepo,
                           MetaColumnRepository columnRepo,
                           MetaRelationRepository relRepo,
                           SysUserRepository userRepo,
                           AuditService audit) {
        this.assetRepo = assetRepo;
        this.columnRepo = columnRepo;
        this.relRepo = relRepo;
        this.userRepo = userRepo;
        this.audit = audit;
    }

    @GetMapping
    public List<AssetSummary> list(@RequestParam(required = false) String keyword,
                                   @RequestParam(required = false) String domain,
                                   @RequestParam(required = false) String grading,
                                   @RequestParam(defaultValue = "200") int limit) {
        List<MetaAsset> assets = (keyword == null || keyword.isBlank())
                ? assetRepo.findAllByOrderByNameAsc()
                : assetRepo.findByNameContainingIgnoreCaseOrderByNameAsc(keyword.trim());
        // M1 drill-down filters (catalog scale is 1322; in-memory filtering is fine)
        if (domain != null && !domain.isBlank()) {
            assets = assets.stream().filter(a -> domain.equalsIgnoreCase(a.getDomainCode())).toList();
        }
        if (grading != null && !grading.isBlank()) {
            assets = assets.stream().filter(a -> grading.equalsIgnoreCase(a.getGrading())).toList();
        }
        return assets.stream().limit(limit).map(AssetController::toSummary).toList();
    }

    /**
     * Facet counts for the catalog sidebar (drill-down facets, PLAN M1.3), explicitly bucketed
     * so a grading code (A/B/C) and a pseudo-domain code (B/C) can never collide in one map —
     * the previous flat Map&lt;domainCode,count&gt; made the frontend read facets["A"] and always
     * show the A-level chip empty while B/C chips silently showed pseudo-domain totals.
     */
    @GetMapping("/facets")
    public Facets facets() {
        Map<String, Long> byDomain = new TreeMap<>();
        Map<String, Long> byGrading = new TreeMap<>();
        for (MetaAsset a : assetRepo.findAll()) {
            byDomain.merge(a.getDomainCode() == null ? "?" : a.getDomainCode(), 1L, Long::sum);
            byGrading.merge(a.getGrading() == null ? "?" : a.getGrading(), 1L, Long::sum);
        }
        return new Facets(byDomain, byGrading, assetRepo.count());
    }

    @GetMapping("/count")
    public long count() {
        return assetRepo.count();
    }

    @GetMapping("/by-name/{name}")
    public ResponseEntity<AssetDetail> byName(@PathVariable String name) {
        return assetRepo.findAllByOrderByNameAsc().stream()
                .filter(a -> a.getName().equalsIgnoreCase(name))
                .findFirst()
                .map(this::toDetail)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/detail")
    public ResponseEntity<AssetDetail> detail(@RequestParam String urn) {
        return assetRepo.findByAssetUrn(urn)
                .map(this::toDetail)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    private AssetDetail toDetail(MetaAsset a) {
        List<ColumnView> cols = columnRepo.findByAssetIdOrderByOrdinalAsc(a.getId()).stream()
                .map(AssetController::toColumn)
                .toList();
        return new AssetDetail(toSummary(a), cols, relationsOf(a));
    }

    /** outgoing + incoming inferred edges with resolved endpoint names (M1.3 detail aggregation) */
    private List<RelationView> relationsOf(MetaAsset a) {
        Function<Long, String> nameOf = id -> id == null ? null
                : assetRepo.findById(id).map(MetaAsset::getName).orElse(null);
        List<RelationView> out = new ArrayList<>();
        for (MetaRelation r : relRepo.findByFromAssetId(a.getId())) {
            out.add(toRelation(r, "out", nameOf));
        }
        for (MetaRelation r : relRepo.findByToAssetId(a.getId())) {
            out.add(toRelation(r, "in", nameOf));
        }
        return out;
    }

    @GetMapping("/{id}/relations")
    public ResponseEntity<List<RelationView>> relations(@PathVariable Long id) {
        return assetRepo.findById(id)
                .map(a -> ResponseEntity.ok(relationsOf(a)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** M1.5 governance attribute edit (certification / sensitivity / deprecation / owner) */
    @PatchMapping("/{id}/governance")
    public ResponseEntity<AssetSummary> patchGovernance(@PathVariable Long id,
                                                        @RequestBody GovernanceUpdate g) {
        Optional<MetaAsset> found = assetRepo.findById(id);
        if (found.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        MetaAsset a = found.get();
        // D5 (2026-10-03): owner/steward resolve against the authoritative sys_user identity
        // source; a dangling reference is rejected as a client error (GlobalExceptionHandler -> 400).
        requireUserExists(g.ownerId(), "owner_id");
        requireUserExists(g.stewardId(), "steward_id");
        if (g.certificationStatus() != null) {
            a.setCertificationStatus(g.certificationStatus());
        }
        if (g.sensitivityLevel() != null) {
            a.setSensitivityLevel(g.sensitivityLevel());
        }
        if (g.deprecated() != null) {
            a.setDeprecated(g.deprecated());
        }
        if (g.deprecationNote() != null) {
            a.setDeprecationNote(g.deprecationNote());
        }
        if (g.ownerId() != null) {
            a.setOwnerId(g.ownerId());
        }
        if (g.stewardId() != null) {
            a.setStewardId(g.stewardId());
        }
        MetaAsset saved = assetRepo.save(a);
        audit.record("GOVERNANCE_UPDATE", saved.getAssetUrn(), g.toString());
        return ResponseEntity.ok(toSummary(saved));
    }

    private void requireUserExists(Long userId, String field) {
        if (userId != null && userRepo.findById(userId).isEmpty()) {
            throw new IllegalArgumentException(field + " 不存在于 sys_user: " + userId);
        }
    }

    /**
     * M9.1 field drag-sort: persist a new column order for an asset. The payload is the full
     * ordered id list; ordinals are re-assigned 0..n-1. Rejects (400) any payload that does not
     * cover exactly this asset's columns, so a stale/dragged foreign id can never corrupt order.
     */
    @PatchMapping("/{id}/columns/order")
    public ResponseEntity<List<ColumnView>> reorderColumns(@PathVariable Long id,
                                                           @RequestBody ColumnOrder req) {
        if (assetRepo.findById(id).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        List<MetaColumn> cols = columnRepo.findByAssetIdOrderByOrdinalAsc(id);
        Map<Long, MetaColumn> byId = cols.stream()
                .collect(Collectors.toMap(MetaColumn::getId, Function.identity()));
        List<Long> ordered = req.columnIds() == null ? List.of() : req.columnIds();
        if (ordered.size() != cols.size() || !byId.keySet().containsAll(ordered)) {
            throw new IllegalArgumentException("列顺序必须恰好覆盖该表全部列（不含缺失或外表列）");
        }
        for (int i = 0; i < ordered.size(); i++) {
            byId.get(ordered.get(i)).setOrdinal(i);
        }
        List<MetaColumn> saved = columnRepo.saveAll(cols);
        audit.record("COLUMN_REORDER", id.toString(), ordered.size() + " columns");
        return ResponseEntity.ok(saved.stream()
                .sorted((a, b) -> Integer.compare(a.getOrdinal(), b.getOrdinal()))
                .map(AssetController::toColumn).toList());
    }

    private static RelationView toRelation(MetaRelation r, String direction,
                                          Function<Long, String> nameOf) {
        return new RelationView(r.getId(), direction,
                nameOf.apply(r.getFromAssetId()), r.getFromColumn(),
                nameOf.apply(r.getToAssetId()), r.getToColumn(),
                r.getTargetRaw(), r.getEvidenceLevel(), r.getCardinality(), r.isConflictFlag(), r.getOrigin(),
                r.getConfidence(), r.getConfirmStatus(), r.getCrossDomain(), r.getSourceDoc());
    }

    static AssetSummary toSummary(MetaAsset a) {
        return new AssetSummary(a.getId(), a.getAssetUrn(), a.getName(), a.getGrading(), a.getDomainCode(),
                a.getPrefixFamily(), a.getHasPk(), a.getColumnCount(), a.getTableComment(),
                a.getCertificationStatus(), a.getSensitivityLevel(), a.getDeprecated(),
                a.getOwnerId(), a.getStewardId());
    }

    static ColumnView toColumn(MetaColumn c) {
        return new ColumnView(c.getId(), c.getOrdinal(), c.getName(), c.getType(), c.getNullable(),
                c.getDefaultVal(), c.getKeyHint(), c.getMeaning(), c.getSourceLayer());
    }
}
