package com.dam.web;

import com.dam.audit.AuditService;
import com.dam.domain.MetaAsset;
import com.dam.domain.MetaColumn;
import com.dam.domain.MetaRelation;
import com.dam.repository.MetaAssetRepository;
import com.dam.repository.MetaColumnRepository;
import com.dam.repository.MetaRelationRepository;
import com.dam.web.dto.Dtos.AssetDetail;
import com.dam.web.dto.Dtos.AssetSummary;
import com.dam.web.dto.Dtos.ColumnView;
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
    private final AuditService audit;

    public AssetController(MetaAssetRepository assetRepo,
                           MetaColumnRepository columnRepo,
                           MetaRelationRepository relRepo,
                           AuditService audit) {
        this.assetRepo = assetRepo;
        this.columnRepo = columnRepo;
        this.relRepo = relRepo;
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

    /** Domain/grading counts for the catalog sidebar (drill-down facets, PLAN M1.3). */
    @GetMapping("/facets")
    public Map<String, Long> facets() {
        return assetRepo.findAll().stream()
                .filter(a -> a.getDomainCode() != null)
                .collect(Collectors.groupingBy(
                        MetaAsset::getDomainCode,
                        TreeMap::new,
                        Collectors.counting()));
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

    private static RelationView toRelation(MetaRelation r, String direction,
                                          Function<Long, String> nameOf) {
        return new RelationView(r.getId(), direction,
                nameOf.apply(r.getFromAssetId()), r.getFromColumn(),
                nameOf.apply(r.getToAssetId()), r.getToColumn(),
                r.getTargetRaw(), r.getEvidenceLevel(), r.getOrigin(),
                r.getConfidence(), r.getConfirmStatus(), r.getCrossDomain(), r.getSourceDoc());
    }

    static AssetSummary toSummary(MetaAsset a) {
        return new AssetSummary(a.getAssetUrn(), a.getName(), a.getGrading(), a.getDomainCode(),
                a.getPrefixFamily(), a.getHasPk(), a.getColumnCount(), a.getTableComment(),
                a.getCertificationStatus(), a.getSensitivityLevel(), a.getDeprecated(),
                a.getOwnerId(), a.getStewardId());
    }

    static ColumnView toColumn(MetaColumn c) {
        return new ColumnView(c.getOrdinal(), c.getName(), c.getType(), c.getNullable(),
                c.getDefaultVal(), c.getKeyHint(), c.getMeaning(), c.getSourceLayer());
    }
}
