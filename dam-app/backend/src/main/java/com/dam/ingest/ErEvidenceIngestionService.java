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
 * <b>cardinality</b>, can strengthen the five-level <b>evidence</b>, and can <b>resolve</b> the
 * ~54 channel-2 edges whose target was named only in prose.
 *
 * <p>Zero-fabrication discipline mirrors the M1 B-1 invariant and the relationship-symbol
 * direction: an edge is materialised ONLY when both endpoint table names resolve to real catalog
 * assets AND the FK-owning ("many") side really contains the named column. The symbol's left/right
 * markers decide which side owns the FK: a clearly-oriented one&lt;-&gt;many line may create a NEW
 * directed edge; an ambiguous 1:1 line (both markers "one/optional") may only enrich an existing
 * channel-2 edge (whose direction channel-2 already established); an M:N line is skipped entirely
 * (it is not a single directed FK). Nothing is guessed.
 *
 * <p><b>ER-priority merge</b> keyed on {@code (fromAssetId, fromColumn)}: a matching channel-2 edge
 * is enriched (cardinality / stronger evidence / back-filled target column) and, if its target was
 * unresolved prose, resolved to the ER parent — its {@code origin} stays 逻辑FK列 so the accepted
 * M1 channel-2 baseline (413 edges) is never re-counted. When channel-2 already points at a
 * different concrete target the two sources conflict: the edge is flagged for the confirmation
 * workbench, not silently overwritten. Human-rejected (驳回) edges are left untouched. Idempotent:
 * a re-run only re-enriches already-consistent keys.
 */
@Service
public class ErEvidenceIngestionService {

    private static final Logger log = LoggerFactory.getLogger(ErEvidenceIngestionService.class);
    public static final String ORIGIN_ER = "ER证据摘录";

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
                           int resolvedProse, int conflicts, int skippedEndpoint, int skippedColumn,
                           int skippedMulti, int skippedAmbiguous, int skippedReverse) {
        @Override
        public String toString() {
            return "ErReport{matched=" + matched + ", cardinalityFilled=" + cardinalityFilled
                    + ", evidenceUpgraded=" + evidenceUpgraded + ", newEdges=" + newEdges
                    + ", resolvedProse=" + resolvedProse + ", conflicts=" + conflicts
                    + ", skippedEndpoint=" + skippedEndpoint + ", skippedColumn=" + skippedColumn
                    + ", skippedMulti=" + skippedMulti + ", skippedAmbiguous=" + skippedAmbiguous
                    + ", skippedReverse=" + skippedReverse + "}";
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
        // index every existing edge by (fromAssetId, fromColumn) so ER-priority merge is done there
        Map<String, List<MetaRelation>> edgesAt = new HashMap<>();
        for (MetaRelation r : relRepo.findAll()) {
            edgesAt.computeIfAbsent(posKey(r.getFromAssetId(), r.getFromColumn()), k -> new ArrayList<>()).add(r);
        }

        Counters c = new Counters();
        List<MetaRelation> newEdges = new ArrayList<>();

        try (Stream<Path> paths = Files.list(erDir)) {
            List<Path> docs = paths.filter(p -> p.toString().endsWith(".md")).sorted().toList();
            for (Path doc : docs) {
                String relPath = "01-ER图/" + doc.getFileName();
                List<ParsedErRelation> parsed =
                        ErDiagramRelationParser.parse(Files.readAllLines(doc, StandardCharsets.UTF_8));
                for (ParsedErRelation pr : parsed) {
                    overlay(pr, byName, colsByAsset, edgesAt, newEdges, relPath, c);
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to scan " + erDir, e);
        }

        relRepo.saveAll(newEdges);
        ErReport report = new ErReport(c.matched, c.filled, c.upgraded, newEdges.size(),
                c.resolvedProse, c.conflicts, c.skippedEndpoint, c.skippedColumn,
                c.skippedMulti, c.skippedAmbiguous, c.skippedReverse);
        log.info("ER evidence overlaid: {}", report);
        return report;
    }

    private void overlay(ParsedErRelation pr, Map<String, MetaAsset> byName,
                         Map<Long, Set<String>> colsByAsset, Map<String, List<MetaRelation>> edgesAt,
                         List<MetaRelation> newEdges, String doc, Counters c) {
        if (pr.getKind() == ParsedErRelation.Kind.MANY_TO_MANY) {
            c.skippedMulti++;   // not a single directed FK
            return;
        }
        MetaAsset left = byName.get(pr.getLeftTable().toLowerCase(Locale.ROOT));
        MetaAsset right = byName.get(pr.getRightTable().toLowerCase(Locale.ROOT));
        if (left == null || right == null) {
            c.skippedEndpoint++;   // endpoint not in catalog: never fabricate an asset
            return;
        }
        // candidate (child, parent) orientations, ordered by the symbol's certainty
        List<MetaAsset[]> orientations = new ArrayList<>();
        if (pr.getKind() == ParsedErRelation.Kind.ONE_TO_MANY) {
            orientations.add(new MetaAsset[]{right, left});           // child = many side (right)
        } else if (pr.getKind() == ParsedErRelation.Kind.MANY_TO_ONE) {
            orientations.add(new MetaAsset[]{left, right});           // child = many side (left)
        } else { // ONE_TO_ONE: FK side ambiguous -> enrich-only, try both, never create
            orientations.add(new MetaAsset[]{right, left});
            orientations.add(new MetaAsset[]{left, right});
        }
        for (MetaAsset[] cp : orientations) {
            if (apply(pr, cp[0], cp[1], colsByAsset, edgesAt, newEdges, doc, c)) {
                return;   // this orientation consumed the line
            }
        }
        // none matched/enriched: ambiguous 1:1 with no channel-2 counterpart -> do not create (S1)
        if (pr.getKind() == ParsedErRelation.Kind.ONE_TO_ONE) {
            c.skippedAmbiguous++;
        } else {
            c.skippedColumn++;   // clear direction but the child owns no named column
        }
    }

    /** @return true if the line was materialised onto an existing edge or a new edge was created. */
    private boolean apply(ParsedErRelation pr, MetaAsset child, MetaAsset parent,
                          Map<Long, Set<String>> colsByAsset, Map<String, List<MetaRelation>> edgesAt,
                          List<MetaRelation> newEdges, String doc, Counters c) {
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
            prose.setToAssetId(parent.getId());   // ER resolves channel-2's prose-only target
            if (toCol != null) {
                prose.setToColumn(toCol);
            }
            prose.setTargetRaw(parent.getName());
            appendSource(prose, doc);
            enrich(prose, pr, toCol, doc, c);
            c.resolvedProse++;
            c.matched++;
            return true;
        }
        if (!at.isEmpty()) {
            // channel-2 already points this (from,col) at a different concrete target -> conflict
            MetaRelation first = at.get(0);
            markConflict(first, parent);
            c.conflicts++;
            return true;
        }
        if (pr.allowsNewEdge()) {
            // 方向兜底：若目录中已存在【同列名反向】边，则说明该关系的真实方向已被确立（②或先前的①），
            // 此条符号多端与 alias 归属冲突，绝不新建反向假边（S1：符号标记不足以定 FK 归属时保守舍弃）。
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
        String note = "｜ER冲突目标:" + erParent.getName();
        String basis = e.getBasisRaw() == null ? "" : e.getBasisRaw();
        if (!basis.contains(note)) {
            e.setBasisRaw(cut(basis + note, 300));
        }
    }

    private void appendSource(MetaRelation e, String doc) {
        String tag = "｜ER:" + doc;
        String basis = e.getBasisRaw() == null ? "" : e.getBasisRaw();
        if (!basis.contains(doc)) {
            e.setBasisRaw(cut(basis + tag, 300));
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

    /** mutable tally for one ingest run (avoids a dozen return-value plumbing). */
    private static final class Counters {
        int matched, filled, upgraded, resolvedProse, conflicts,
            skippedEndpoint, skippedColumn, skippedMulti, skippedAmbiguous, skippedReverse;
    }
}
