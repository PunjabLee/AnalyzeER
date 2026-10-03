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
 * <b>cardinality</b> and can strengthen the five-level <b>evidence</b> of an existing edge.
 *
 * <p>Zero-fabrication discipline mirrors the M1 B-1 invariant: an ER edge is materialised ONLY
 * when both endpoint table names resolve to real catalog assets AND the child table actually owns
 * the FK column named in the label. Relations whose endpoints/columns cannot be resolved are
 * skipped (logged), never guessed.
 *
 * <p><b>ER-priority merge</b>: for an edge already present from channel-2 the cardinality is
 * filled (if empty), the target column back-filled, and the evidence upgraded when the ER tag is
 * stronger — but the row's {@code origin} stays 逻辑FK列 so the accepted M1 channel-2 baseline
 * (413 edges) is never re-counted. ER edges absent from channel-2 are inserted as new rows with
 * {@code origin=ER证据摘录}. The method is idempotent: a re-run only finds already-enriched keys.
 */
@Service
public class ErEvidenceIngestionService {

    private static final Logger log = LoggerFactory.getLogger(ErEvidenceIngestionService.class);
    private static final String ORIGIN_ER = "ER证据摘录";

    private final MetaAssetRepository assetRepo;
    private final MetaColumnRepository columnRepo;
    private final MetaRelationRepository relRepo;

    public ErEvidenceIngestionService(MetaAssetRepository assetRepo, MetaColumnRepository columnRepo,
                                      MetaRelationRepository relRepo) {
        this.assetRepo = assetRepo;
        this.columnRepo = columnRepo;
        this.relRepo = relRepo;
    }

    public record ErReport(int matched, int cardinalityFilled, int evidenceUpgraded,
                           int newEdges, int skipped) {
        @Override
        public String toString() {
            return "ErReport{matched=" + matched + ", cardinalityFilled=" + cardinalityFilled
                    + ", evidenceUpgraded=" + evidenceUpgraded + ", newEdges=" + newEdges
                    + ", skipped=" + skipped + "}";
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
        // index every existing edge by its identity key so we enrich in place instead of duplicating
        Map<String, MetaRelation> edgeByKey = new HashMap<>();
        for (MetaRelation r : relRepo.findAll()) {
            edgeByKey.putIfAbsent(key(r.getFromAssetId(), r.getFromColumn(), r.getToAssetId()), r);
        }

        Set<String> seenNew = new HashSet<>();
        List<MetaRelation> newEdges = new ArrayList<>();
        int matched = 0, filled = 0, upgraded = 0, skipped = 0;

        try (Stream<Path> paths = Files.list(erDir)) {
            List<Path> docs = paths.filter(p -> p.toString().endsWith(".md")).sorted().toList();
            for (Path doc : docs) {
                String relPath = "01-ER图/" + doc.getFileName();
                List<ParsedErRelation> parsed =
                        ErDiagramRelationParser.parse(Files.readAllLines(doc, StandardCharsets.UTF_8));
                for (ParsedErRelation pr : parsed) {
                    MetaAsset child = byName.get(pr.getChildTable().toLowerCase(Locale.ROOT));
                    MetaAsset parent = byName.get(pr.getParentTable().toLowerCase(Locale.ROOT));
                    if (child == null || parent == null) {
                        skipped++;   // endpoint not in catalog: never fabricate an asset
                        continue;
                    }
                    String fkCol = firstOwned(pr.getFromColumnCandidates(), columns(colsByAsset, child.getId()));
                    if (fkCol == null) {
                        skipped++;   // child table owns none of the named columns: cannot place the edge
                        continue;
                    }
                    String toCol = pr.getToColumnCandidate() != null
                            && columns(colsByAsset, parent.getId()).contains(pr.getToColumnCandidate().toLowerCase(Locale.ROOT))
                            ? pr.getToColumnCandidate() : null;

                    String k = key(child.getId(), fkCol, parent.getId());
                    MetaRelation existing = edgeByKey.get(k);
                    if (existing != null) {
                        matched++;
                        if (existing.getCardinality() == null) {
                            existing.setCardinality(pr.getCardinality());
                            filled++;
                        }
                        if (existing.getToColumn() == null && toCol != null) {
                            existing.setToColumn(toCol);
                        }
                        if (ErDiagramRelationParser.rank(pr.getEvidenceLevel())
                                < ErDiagramRelationParser.rank(existing.getEvidenceLevel())) {
                            existing.setEvidenceLevel(pr.getEvidenceLevel());
                            existing.setConfidence(pr.getConfidence());
                            upgraded++;
                        }
                    } else if (seenNew.add(k)) {
                        newEdges.add(newEdge(child, parent, fkCol, toCol, pr, relPath));
                    }
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to scan " + erDir, e);
        }

        relRepo.saveAll(newEdges);
        ErReport report = new ErReport(matched, filled, upgraded, newEdges.size(), skipped);
        log.info("ER evidence overlaid: {}", report);
        return report;
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
        r.setBasisRaw(cut(pr.getLabelRaw(), 300));
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

    private static String key(Long fromId, String fromColumn, Long toId) {
        return fromId + "\u0001" + (fromColumn == null ? "" : fromColumn.toLowerCase(Locale.ROOT))
                + "\u0001" + toId;
    }

    private static String cut(String s, int max) {
        if (s == null || s.isEmpty()) {
            return s;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}
