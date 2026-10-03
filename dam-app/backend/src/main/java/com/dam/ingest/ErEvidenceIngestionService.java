package com.dam.ingest;

import com.dam.domain.MetaAsset;
import com.dam.domain.MetaColumn;
import com.dam.domain.MetaRelation;
import com.dam.parser.ErDiagramRelationParser;
import com.dam.parser.ErModelSourceLocator;
import com.dam.parser.ParsedErRelation;
import com.dam.repository.MetaAssetRepository;
import com.dam.repository.MetaColumnRepository;
import com.dam.repository.MetaRelationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Channel-1 ingestion (PLAN §1.1 双通道 / D1): overlays the ER evidence carried by
 * {@code er-model/01-ER图/*.md} Mermaid relation lines onto the relation store built by
 * channel-2 ({@link RelationIngestionService}). Channel-1 is the only source of
 * <b>cardinality</b>, can strengthen the five-level <b>evidence</b>, and — only when the line is
 * trustworthy — can <b>resolve</b> a channel-2 edge whose target was named only in prose.
 *
 * <p>Zero-fabrication discipline mirrors the M1 B-1 invariant: an edge is materialised ONLY when
 * both endpoint table names resolve to real catalog assets AND the FK-owning ("many") side really
 * contains the named column. The symbol's left/right markers decide which side owns the FK; a
 * clearly-oriented one&lt;-&gt;many line may create a NEW edge; an ambiguous 1:1 line may only enrich
 * an existing channel-2 edge; an M:N line is skipped (not a single directed FK); and any line whose
 * symbol contradicts its own label cardinality, or whose symbol is malformed, is skipped (S1-3/S2-1).
 *
 * <p><b>Prose resolution is double-gated (S1-1/S1-2 remediation):</b> a channel-2 prose edge
 * ({@code to_asset_id == null}) is pinned to a concrete parent ONLY when the ER line both
 * (a) carries no self-denial keyword (非id / 无 id / 多值 / 多单号串 / 名称关联 / 存名称 / 弱关联 /
 * 亦可能 / 二选一 / 待确认 …) and (b) is the sole parent candidate for that {@code (child, fkColumn)}
 * position across the whole corpus. Otherwise the ER candidate is merely recorded in
 * {@code basis_raw} for the confirmation workbench and the edge stays unresolved.
 *
 * <p><b>ER-priority merge</b> keyed on {@code (fromAssetId, fromColumn)}: channel-2's original
 * {@code target_raw} (its only raw evidence text) is NEVER overwritten — a resolved target is
 * recorded via {@code basis_raw} so accepted M1 facts stay distinguishable from channel-1 inferences.
 * When channel-2 already points at a different concrete target the two sources conflict: the edge is
 * flagged, not silently rewritten. Human-rejected (驳回) edges are left untouched. Idempotent.
 */
@Service
public class ErEvidenceIngestionService {

    private static final Logger log = LoggerFactory.getLogger(ErEvidenceIngestionService.class);
    public static final String ORIGIN_ER = "ER证据摘录";

    /** ER line-label keywords that self-deny a resolvable FK → prose must NOT be pinned (S1-1). */
    private static final String[] DENIAL_KEYWORDS = {
            "非id", "非 id", "无id", "无 id", "多值", "多单号串", "名称关联", "存名称", "存 名称",
            "弱关联", "报文匹配", "亦可能", "二选一", "待确认", "无 FK", "无FK"};

    private final MetaAssetRepository assetRepo;
    private final MetaColumnRepository columnRepo;
    private final MetaRelationRepository relRepo;

    public ErEvidenceIngestionService(MetaAssetRepository assetRepo, MetaColumnRepository columnRepo,
                                      MetaRelationRepository relRepo) {
        this.assetRepo = assetRepo;
        this.columnRepo = columnRepo;
        this.relRepo = relRepo;
    }

    public record ErReport(int matched, int cardinalityFilled, int evidenceUpgraded, int newEdges,
                           int resolvedProse, int deferredDenial, int deferredMulti, int conflicts,
                           int skippedEndpoint, int skippedNoColumn, int skippedNotOwned,
                           int skippedMulti, int skippedAmbiguous, int skippedUnsupported,
                           int cardinalityClash, int skippedReverse) {
        @Override
        public String toString() {
            return "ErReport{matched=" + matched + ", cardinalityFilled=" + cardinalityFilled
                    + ", evidenceUpgraded=" + evidenceUpgraded + ", newEdges=" + newEdges
                    + ", resolvedProse=" + resolvedProse + ", deferredDenial=" + deferredDenial
                    + ", deferredMulti=" + deferredMulti + ", conflicts=" + conflicts
                    + ", skippedEndpoint=" + skippedEndpoint + ", skippedNoColumn=" + skippedNoColumn
                    + ", skippedNotOwned=" + skippedNotOwned + ", skippedMulti=" + skippedMulti
                    + ", skippedAmbiguous=" + skippedAmbiguous + ", skippedUnsupported=" + skippedUnsupported
                    + ", cardinalityClash=" + cardinalityClash + ", skippedReverse=" + skippedReverse + "}";
        }
    }

    @Transactional
    public ErReport ingest(String erModelDir) {
        Path dir = ErModelSourceLocator.locateDir(erModelDir);
        Path erDir = dir.resolve("01-ER图");

        Map<String, MetaAsset> byName = new HashMap<>();
        for (MetaAsset a : assetRepo.findAllByOrderByNameAsc()) {
            byName.put(a.getName().toLowerCase(Locale.ROOT), a);
        }
        Map<Long, Set<String>> colsByAsset = new HashMap<>();
        Map<String, List<MetaRelation>> edgesAt = new HashMap<>();
        for (MetaRelation r : relRepo.findAll()) {
            edgesAt.computeIfAbsent(posKey(r.getFromAssetId(), r.getFromColumn()), k -> new ArrayList<>()).add(r);
        }

        // read every ER line once (keep the source doc for provenance)
        List<Sourced> corpus = new ArrayList<>();
        try (Stream<Path> paths = Files.list(erDir)) {
            List<Path> docs = paths.filter(p -> p.toString().endsWith(".md")).sorted().toList();
            for (Path doc : docs) {
                String relPath = "01-ER图/" + doc.getFileName();
                for (ParsedErRelation pr : ErDiagramRelationParser.parse(
                        Files.readAllLines(doc, StandardCharsets.UTF_8))) {
                    corpus.add(new Sourced(pr, relPath));
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to scan " + erDir, e);
        }

        // S1-2 pre-pass: count distinct parent candidates per (child, fkColumn) across the corpus so
        // a position targeted by more than one clearly-oriented ER line is never auto-resolved.
        Map<String, Set<Long>> parentsAt = new HashMap<>();
        for (Sourced s : corpus) {
            ParsedErRelation pr = s.pr();
            if (!pr.allowsNewEdge()) {
                continue;
            }
            MetaAsset[] cp = orient(pr, byName);
            if (cp == null) {
                continue;
            }
            String fkCol = firstOwned(pr.getFromColumnCandidates(), columns(colsByAsset, cp[0].getId()));
            if (fkCol != null) {
                parentsAt.computeIfAbsent(posKey(cp[0].getId(), fkCol), k -> new HashSet<>()).add(cp[1].getId());
            }
        }

        Counters c = new Counters();
        List<MetaRelation> newEdges = new ArrayList<>();
        for (Sourced s : corpus) {
            overlay(s.pr(), byName, colsByAsset, edgesAt, parentsAt, newEdges, s.doc(), c);
        }
        relRepo.saveAll(newEdges);

        ErReport report = new ErReport(c.matched, c.filled, c.upgraded, newEdges.size(),
                c.resolvedProse, c.deferredDenial, c.deferredMulti, c.conflicts, c.skippedEndpoint,
                c.skippedNoColumn, c.skippedNotOwned, c.skippedMulti, c.skippedAmbiguous,
                c.skippedUnsupported, c.cardinalityClash, c.skippedReverse);
        log.info("ER evidence overlaid: {}", report);
        return report;
    }

    /** unique (child, parent) for a clearly-oriented line, or null if an endpoint is not in the catalog. */
    private MetaAsset[] orient(ParsedErRelation pr, Map<String, MetaAsset> byName) {
        MetaAsset left = byName.get(pr.getLeftTable().toLowerCase(Locale.ROOT));
        MetaAsset right = byName.get(pr.getRightTable().toLowerCase(Locale.ROOT));
        if (left == null || right == null) {
            return null;
        }
        return pr.getKind() == ParsedErRelation.Kind.ONE_TO_MANY
                ? new MetaAsset[]{right, left}   // child = many side (right)
                : new MetaAsset[]{left, right};  // MANY_TO_ONE: child = many side (left)
    }

    private void overlay(ParsedErRelation pr, Map<String, MetaAsset> byName,
                         Map<Long, Set<String>> colsByAsset, Map<String, List<MetaRelation>> edgesAt,
                         Map<String, Set<Long>> parentsAt, List<MetaRelation> newEdges, String doc, Counters c) {
        if (pr.getKind() == ParsedErRelation.Kind.UNSUPPORTED_SYMBOL) {
            c.skippedUnsupported++;   // malformed marker: counted, never silently dropped (S2-1)
            return;
        }
        if (pr.getKind() == ParsedErRelation.Kind.AMBIGUOUS) {
            c.cardinalityClash++;     // symbol vs label cardinality disagree → untrusted (S1-3)
            return;
        }
        if (pr.getKind() == ParsedErRelation.Kind.MANY_TO_MANY) {
            c.skippedMulti++;         // not a single directed FK
            return;
        }
        MetaAsset left = byName.get(pr.getLeftTable().toLowerCase(Locale.ROOT));
        MetaAsset right = byName.get(pr.getRightTable().toLowerCase(Locale.ROOT));
        if (left == null || right == null) {
            c.skippedEndpoint++;      // endpoint not in catalog: never fabricate an asset
            return;
        }
        // candidate (child, parent) orientations, ordered by the symbol's certainty
        List<MetaAsset[]> orientations = new ArrayList<>();
        if (pr.getKind() == ParsedErRelation.Kind.ONE_TO_MANY) {
            orientations.add(new MetaAsset[]{right, left});
        } else if (pr.getKind() == ParsedErRelation.Kind.MANY_TO_ONE) {
            orientations.add(new MetaAsset[]{left, right});
        } else { // ONE_TO_ONE: FK side ambiguous -> enrich-only, try both, never create/resolve
            orientations.add(new MetaAsset[]{right, left});
            orientations.add(new MetaAsset[]{left, right});
        }
        for (MetaAsset[] cp : orientations) {
            if (apply(pr, cp[0], cp[1], colsByAsset, edgesAt, parentsAt, newEdges, doc, c)) {
                return;   // this orientation consumed the line
            }
        }
        if (pr.getKind() == ParsedErRelation.Kind.ONE_TO_ONE) {
            c.skippedAmbiguous++;   // ambiguous 1:1 with no channel-2 counterpart → do not create (S1)
        } else if (pr.getFromColumnCandidates().isEmpty()) {
            c.skippedNoColumn++;    // no extractable column token at all (S2-4)
        } else {
            c.skippedNotOwned++;    // clear direction but neither side owns a named column
        }
    }

    /** @return true if the line was materialised onto an existing edge or a new edge was created. */
    private boolean apply(ParsedErRelation pr, MetaAsset child, MetaAsset parent,
                          Map<Long, Set<String>> colsByAsset, Map<String, List<MetaRelation>> edgesAt,
                          Map<String, Set<Long>> parentsAt, List<MetaRelation> newEdges, String doc, Counters c) {
        String fkCol = firstOwned(pr.getFromColumnCandidates(), columns(colsByAsset, child.getId()));
        if (fkCol == null) {
            return false;   // this child table owns none of the named columns: try next orientation
        }
        String toCol = pr.getToColumnCandidate() != null
                && columns(colsByAsset, parent.getId()).contains(pr.getToColumnCandidate().toLowerCase(Locale.ROOT))
                ? pr.getToColumnCandidate() : null;

        List<MetaRelation> at = edgesAt.computeIfAbsent(
                posKey(child.getId(), fkCol), k -> new ArrayList<>());
        if (at.stream().anyMatch(e -> "驳回".equals(e.getConfirmStatus()))) {
            return true;    // a human rejected this FK position: leave it, treat line as consumed
        }
        MetaRelation exact = at.stream()
                .filter(e -> parent.getId().equals(e.getToAssetId())).findFirst().orElse(null);
        if (exact != null) {
            enrich(exact, pr, toCol, doc, c);
            c.matched++;
            return true;
        }
        MetaRelation prose = at.stream().filter(e -> e.getToAssetId() == null).findFirst().orElse(null);
        if (prose != null) {
            c.matched++;
            resolveProse(pr, prose, child, parent, toCol, doc, parentsAt, c);
            return true;
        }
        if (!at.isEmpty()) {
            MetaRelation first = at.get(0);   // channel-2 already points this (from,col) at a different target
            markConflict(first, parent);
            c.conflicts++;
            return true;
        }
        if (pr.allowsNewEdge()) {
            // 方向兜底：若目录中已存在【同列名反向】边，则该关系真实方向已被确立，绝不新建反向假边（S1）。
            List<MetaRelation> reverse = edgesAt.getOrDefault(posKey(parent.getId(), fkCol), List.of());
            if (reverse.stream().anyMatch(e -> child.getId().equals(e.getToAssetId()))) {
                c.skippedReverse++;
                return true;
            }
            MetaRelation r = newEdge(child, parent, fkCol, toCol, pr, doc);
            newEdges.add(r);
            at.add(r);   // idempotent: a later line hitting the same (child,fk) enriches instead
        }
        return pr.allowsNewEdge();
    }

    /** Pin a channel-2 prose edge ONLY if the ER line is trustworthy; otherwise just record the candidate. */
    private void resolveProse(ParsedErRelation pr, MetaRelation prose, MetaAsset child, MetaAsset parent,
                              String toCol, String doc, Map<String, Set<Long>> parentsAt, Counters c) {
        if (!pr.canResolveProse()) {
            return;   // 1:1 line cannot decide the FK side: enrich nothing on target (S1-1)
        }
        if (denied(pr.getLabelRaw())) {
            appendCandidate(prose, parent, doc, "自证否认");
            c.deferredDenial++;
            return;
        }
        Set<Long> candParents = parentsAt.getOrDefault(posKey(child.getId(), prose.getFromColumn()), Set.of());
        if (candParents.size() > 1) {
            appendCandidate(prose, parent, doc, "多父候选");
            c.deferredMulti++;
            return;
        }
        // trustworthy: pin the target but NEVER overwrite channel-2's raw target text (S2-3)
        prose.setToAssetId(parent.getId());
        if (toCol != null) {
            prose.setToColumn(toCol);
        }
        appendResolved(prose, parent, doc);
        enrich(prose, pr, toCol, doc, c);
        c.resolvedProse++;
    }

    private static boolean denied(String labelRaw) {
        if (labelRaw == null) {
            return false;
        }
        for (String k : DENIAL_KEYWORDS) {
            if (labelRaw.contains(k)) {
                return true;
            }
        }
        return false;
    }

    private void enrich(MetaRelation e, ParsedErRelation pr, String toCol, String doc, Counters c) {
        if (e.getCardinality() == null) {
            e.setCardinality(pr.getCardinality());
            c.filled++;
        }
        if (e.getToColumn() == null && toCol != null) {
            e.setToColumn(toCol);
        }
        if (ErDiagramRelationParser.rank(pr.getEvidenceLevel())
                < ErDiagramRelationParser.rank(e.getEvidenceLevel())) {
            e.setEvidenceLevel(pr.getEvidenceLevel());
            e.setConfidence(pr.getConfidence());
            appendSource(e, doc);   // record why the evidence was upgraded (S3-3, confirmation trace)
            c.upgraded++;
        }
    }

    private void markConflict(MetaRelation e, MetaAsset erParent) {
        appendNote(e, "｜ER冲突目标:" + erParent.getName());
    }

    private void appendCandidate(MetaRelation e, MetaAsset erParent, String doc, String why) {
        appendNote(e, "｜ER候选:" + erParent.getName() + "(" + why + ",未消解)");
    }

    private void appendResolved(MetaRelation e, MetaAsset erParent, String doc) {
        appendNote(e, "｜ER消解:" + erParent.getName() + "@" + doc);
    }

    private void appendSource(MetaRelation e, String doc) {
        appendNote(e, "｜ER:" + doc);
    }

    /** append a marker to basis_raw idempotently (never duplicate), truncating to the column width. */
    private void appendNote(MetaRelation e, String note) {
        String basis = e.getBasisRaw() == null ? "" : e.getBasisRaw();
        if (!basis.contains(note)) {
            e.setBasisRaw(cut(basis + note, 300));
        }
    }

    private MetaRelation newEdge(MetaAsset child, MetaAsset parent, String fkCol, String toCol,
                                 ParsedErRelation pr, String doc) {
        MetaRelation r = new MetaRelation();
        r.setFromAssetId(child.getId());
        r.setFromColumn(fkCol);
        r.setToAssetId(parent.getId());
        r.setToColumn(toCol);
        r.setTargetRaw(parent.getName());
        r.setEvidenceLevel(pr.getEvidenceLevel());
        r.setConfidence(pr.getConfidence());
        r.setCardinality(pr.getCardinality());
        r.setOrigin(ORIGIN_ER);
        r.setInferred(true);
        r.setConfirmStatus("待确认");
        r.setCrossDomain(pr.getCrossDomain());
        r.setBasisRaw(cut(pr.getLabelRaw() + "｜ER:" + doc, 300));
        r.setSourceDoc(cut(doc, 200));
        return r;
    }

    private Set<String> columns(Map<Long, Set<String>> cache, Long assetId) {
        return cache.computeIfAbsent(assetId, id -> {
            Set<String> s = new HashSet<>();
            for (MetaColumn c : columnRepo.findByAssetIdOrderByOrdinalAsc(id)) {
                if (c.getName() != null) {
                    s.add(c.getName().toLowerCase(Locale.ROOT));
                }
            }
            return s;
        });
    }

    private static String firstOwned(List<String> candidates, Set<String> childCols) {
        for (String c : candidates) {
            if (childCols.contains(c.toLowerCase(Locale.ROOT))) {
                return c;
            }
        }
        return null;
    }

    private static String posKey(Long fromId, String fromColumn) {
        return fromId + "\u0001" + (fromColumn == null ? "" : fromColumn.toLowerCase(Locale.ROOT));
    }

    private static String cut(String s, int max) {
        if (s == null || s.isEmpty()) {
            return s;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }

    /** one parsed ER line bound to its source document (for provenance notes). */
    private record Sourced(ParsedErRelation pr, String doc) { }

    /** mutable tally for one ingest run (avoids a dozen return-value plumbing). */
    private static final class Counters {
        int matched, filled, upgraded, resolvedProse, deferredDenial, deferredMulti, conflicts,
            skippedEndpoint, skippedNoColumn, skippedNotOwned, skippedMulti, skippedAmbiguous,
            skippedUnsupported, cardinalityClash, skippedReverse;
    }
}
