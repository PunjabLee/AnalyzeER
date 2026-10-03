package com.dam.web;

import com.dam.audit.AuditService;
import com.dam.domain.GlossaryTerm;
import com.dam.domain.GlossaryTermRef;
import com.dam.domain.MetaAsset;
import com.dam.domain.MetaColumn;
import com.dam.repository.GlossaryTermRefRepository;
import com.dam.repository.GlossaryTermRepository;
import com.dam.repository.MetaAssetRepository;
import com.dam.repository.MetaColumnRepository;
import com.dam.repository.SysUserRepository;
import com.dam.web.dto.GlossaryDtos.RefView;
import com.dam.web.dto.GlossaryDtos.TermBinding;
import com.dam.web.dto.GlossaryDtos.TermSummary;
import com.dam.web.dto.GlossaryDtos.TermUpsert;
import com.dam.web.dto.GlossaryDtos.TermView;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Business glossary CRUD + term-to-object references (capability M3, PLAN §3.2-(3)):
 * term management (M3.1) and the M:N binding of a term to a table/column (M3.2), which
 * the lightweight list-drag UI (M9.2) drives. Reads are open; writes are gated by
 * SecurityConfig (POST/PUT/PATCH = ADMIN|STEWARD, DELETE = ADMIN) and land in the audit log.
 *
 * <p>Every reference is validated against its target so a dangling binding can never be
 * persisted (same guard discipline as the governance owner check, D5).
 */
@RestController
@RequestMapping("/api/glossary")
public class GlossaryController {

    private final GlossaryTermRepository termRepo;
    private final GlossaryTermRefRepository refRepo;
    private final MetaAssetRepository assetRepo;
    private final MetaColumnRepository columnRepo;
    private final SysUserRepository userRepo;
    private final AuditService audit;

    public GlossaryController(GlossaryTermRepository termRepo,
                              GlossaryTermRefRepository refRepo,
                              MetaAssetRepository assetRepo,
                              MetaColumnRepository columnRepo,
                              SysUserRepository userRepo,
                              AuditService audit) {
        this.termRepo = termRepo;
        this.refRepo = refRepo;
        this.assetRepo = assetRepo;
        this.columnRepo = columnRepo;
        this.userRepo = userRepo;
        this.audit = audit;
    }

    // ---- term CRUD (M3.1) -------------------------------------------------------------------

    @GetMapping("/terms")
    public List<TermSummary> list(@RequestParam(required = false) String domain,
                                  @RequestParam(required = false) String keyword) {
        return termRepo.findAllByOrderByNameAsc().stream()
                .filter(t -> domain == null || domain.isBlank() || domain.equalsIgnoreCase(t.getDomainCode()))
                .filter(t -> keyword == null || keyword.isBlank()
                        || t.getName().toLowerCase().contains(keyword.toLowerCase()))
                .map(t -> new TermSummary(t.getId(), t.getName(), t.getDomainCode(), t.getStatus(),
                        refRepo.findByTermId(t.getId()).size()))
                .toList();
    }

    @GetMapping("/terms/{id}")
    public ResponseEntity<TermView> get(@PathVariable Long id) {
        return termRepo.findById(id).map(this::toView).map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/terms")
    public ResponseEntity<TermView> create(@RequestBody TermUpsert req) {
        if (req.name() == null || req.name().isBlank()) {
            throw new IllegalArgumentException("术语名称(name)不能为空");
        }
        if (termRepo.findByName(req.name().trim()).isPresent()) {
            throw new IllegalArgumentException("术语名称已存在: " + req.name());
        }
        requireUserExists(req.ownerId(), "owner_id");
        GlossaryTerm t = new GlossaryTerm();
        apply(t, req);
        GlossaryTerm saved = termRepo.save(t);
        audit.record("GLOSSARY_TERM_CREATE", saved.getName(), null);
        return ResponseEntity.ok(toView(saved));
    }

    @PutMapping("/terms/{id}")
    public ResponseEntity<TermView> update(@PathVariable Long id, @RequestBody TermUpsert req) {
        return termRepo.findById(id).map(cur -> {
            if (req.name() != null && !req.name().isBlank()
                    && !req.name().trim().equals(cur.getName())) {
                termRepo.findByName(req.name().trim()).ifPresent(dup -> {
                    if (!dup.getId().equals(id)) {
                        throw new IllegalArgumentException("术语名称已存在: " + req.name());
                    }
                });
            }
            requireUserExists(req.ownerId(), "owner_id");
            apply(cur, req);
            GlossaryTerm saved = termRepo.save(cur);
            audit.record("GLOSSARY_TERM_UPDATE", saved.getName(), null);
            return ResponseEntity.ok(toView(saved));
        }).orElseGet(ResponseEntity.notFound()::build);
    }

    @DeleteMapping("/terms/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        GlossaryTerm cur = termRepo.findById(id).orElse(null);
        if (cur == null) {
            return ResponseEntity.notFound().build();
        }
        refRepo.deleteAll(refRepo.findByTermId(id));
        termRepo.delete(cur);
        audit.record("GLOSSARY_TERM_DELETE", cur.getName(), null);
        return ResponseEntity.noContent().build();
    }

    // ---- term <-> object references (M3.2 / M9.2 drag) --------------------------------------

    @GetMapping("/terms/{id}/refs")
    public ResponseEntity<List<RefView>> refs(@PathVariable Long id) {
        if (!termRepo.existsById(id)) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(refRepo.findByTermId(id).stream().map(this::toRef).toList());
    }

    /** idempotent bind: attaching the same object twice returns the existing reference. */
    @PostMapping("/terms/{id}/refs")
    public ResponseEntity<RefView> bind(@PathVariable Long id, @RequestBody TermBinding b) {
        if (termRepo.findById(id).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        Long assetId = resolveBinding(b);
        String refType = b.columnId() != null ? "COLUMN"
                : (b.refType() == null || b.refType().isBlank() ? "TABLE" : b.refType().toUpperCase());
        final Long boundAssetId = assetId;
        GlossaryTermRef existing = refRepo.findByTermId(id).stream()
                .filter(r -> eq(r.getAssetId(), boundAssetId)
                        && eq(r.getColumnId(), b.columnId())
                        && refType.equals(r.getRefType()))
                .findFirst().orElse(null);
        if (existing != null) {
            return ResponseEntity.ok(toRef(existing));
        }
        GlossaryTermRef r = new GlossaryTermRef();
        r.setTermId(id);
        r.setAssetId(boundAssetId);
        r.setColumnId(b.columnId());
        r.setRefType(refType);
        GlossaryTermRef saved = refRepo.save(r);
        audit.record("GLOSSARY_REF_BIND", "term=" + id + ",asset=" + boundAssetId
                + ",column=" + b.columnId(), refType);
        return ResponseEntity.ok(toRef(saved));
    }

    @DeleteMapping("/refs/{refId}")
    public ResponseEntity<Void> unbind(@PathVariable Long refId) {
        GlossaryTermRef r = refRepo.findById(refId).orElse(null);
        if (r == null) {
            return ResponseEntity.notFound().build();
        }
        refRepo.delete(r);
        audit.record("GLOSSARY_REF_UNBIND", "term=" + r.getTermId() + ",ref=" + refId, null);
        return ResponseEntity.noContent().build();
    }

    /** reverse lookup: which terms reference a given asset (used by the asset detail page) */
    @GetMapping("/by-asset/{assetId}")
    public List<TermSummary> termsForAsset(@PathVariable Long assetId) {
        // dedup by term id first: a term may reference the same asset via multiple refs
        // (e.g. table-level + a column of that table); GlossaryTerm has no equals/hashCode,
        // so .distinct() on freshly-loaded instances would not collapse them (S3-3).
        return refRepo.findByAssetId(assetId).stream()
                .map(GlossaryTermRef::getTermId)
                .distinct()
                .map(tid -> termRepo.findById(tid).orElse(null))
                .filter(java.util.Objects::nonNull)
                .map(t -> new TermSummary(t.getId(), t.getName(), t.getDomainCode(), t.getStatus(),
                        refRepo.findByTermId(t.getId()).size()))
                .toList();
    }

    // ---- helpers ---------------------------------------------------------------------------

    /**
     * Validate a binding and resolve the effective owning asset id.
     * A column reference always carries its owning asset (auto-filled when omitted, and
     * cross-checked against a supplied assetId so a column can never be bound under a
     * foreign table). Returns the asset id to persist.
     */
    private Long resolveBinding(TermBinding b) {
        if (b.columnId() != null) {
            MetaColumn col = columnRepo.findById(b.columnId()).orElseThrow(
                    () -> new IllegalArgumentException("column_id 不存在于 meta_column: " + b.columnId()));
            if (b.assetId() != null && !col.getAssetId().equals(b.assetId())) {
                throw new IllegalArgumentException("column 不属于所声明的 asset: column="
                        + b.columnId() + " 归属 asset=" + col.getAssetId());
            }
            return col.getAssetId();
        }
        if (b.assetId() == null) {
            throw new IllegalArgumentException("绑定必须至少指定 assetId(表) 或 columnId(列)");
        }
        if (assetRepo.findById(b.assetId()).isEmpty()) {
            throw new IllegalArgumentException("asset_id 不存在于 meta_asset: " + b.assetId());
        }
        return b.assetId();
    }

    private void apply(GlossaryTerm t, TermUpsert req) {
        if (req.name() != null && !req.name().isBlank()) {
            t.setName(req.name().trim());
        }
        t.setDefinition(req.definition());
        t.setAliases(req.aliases());
        t.setCaliber(req.caliber());
        t.setDomainCode(req.domainCode());
        t.setOwnerId(req.ownerId());
        t.setNote(req.note());
        if (req.status() != null && !req.status().isBlank()) {
            t.setStatus(req.status().trim().toUpperCase());
        }
    }

    private void requireUserExists(Long userId, String field) {
        if (userId != null && userRepo.findById(userId).isEmpty()) {
            throw new IllegalArgumentException(field + " 不存在于 sys_user: " + userId);
        }
    }

    private TermView toView(GlossaryTerm t) {
        String ownerName = t.getOwnerId() == null ? null
                : userRepo.findById(t.getOwnerId()).map(u -> u.getDisplayName()).orElse(null);
        List<RefView> refs = refRepo.findByTermId(t.getId()).stream().map(this::toRef).toList();
        return new TermView(t.getId(), t.getName(), t.getDefinition(), t.getAliases(), t.getCaliber(),
                t.getDomainCode(), t.getOwnerId(), ownerName, t.getStatus(), t.getNote(), refs);
    }

    private RefView toRef(GlossaryTermRef r) {
        String assetName = r.getAssetId() == null ? null
                : assetRepo.findById(r.getAssetId()).map(MetaAsset::getName).orElse(null);
        String columnName = r.getColumnId() == null ? null
                : columnRepo.findById(r.getColumnId()).map(MetaColumn::getName).orElse(null);
        return new RefView(r.getId(), r.getAssetId(), assetName, r.getColumnId(), columnName, r.getRefType());
    }

    private static boolean eq(Long a, Long b) {
        return a == null ? b == null : a.equals(b);
    }
}
