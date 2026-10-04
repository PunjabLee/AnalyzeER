package com.dam.ingest;

import com.dam.domain.Candidates;
import com.dam.domain.Candidates.Candidate;
import com.dam.domain.MetaAsset;
import com.dam.domain.MetaRelation;
import com.dam.parser.ErModelSourceLocator;
import com.dam.parser.LogicalModelRelationParser;
import com.dam.parser.ParsedRelation;
import com.dam.repository.MetaAssetRepository;
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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * M1 relation channel (PLAN §M1.1 双通道): edges are ingested from
 * er-model/03-逻辑数据模型/*.md "FK[目标·依据]" key cells — never from the DDL,
 * because the governed database declares zero foreign keys.
 *
 * <p>Every edge keeps the five-level evidence, a confidence seed and confirm_status=待确认
 * for the later M5 human confirmation loop. Targets named only in prose keep to_asset_id=null.
 *
 * <p><b>Incremental upsert (C-2 remediation, replaces the old delete-all rebuild):</b> re-running
 * this channel is safe after human confirmation work. Only {@code origin=逻辑FK列} rows are managed;
 * channel-1 ({@code ER证据摘录}) and manual edges are never touched. Edge identity is
 * {@code (fromAssetId, fromColumn, targetRaw)} — targetRaw is channel-2's own raw text which
 * channel-1 never overwrites (S2-3), so the key is stable across overlays. On a matched row the
 * document-derived facts are refreshed, while human/ER verdicts are preserved:
 * {@code confirm_status}/{@code conflict_flag}/{@code cardinality} are never rewritten, an
 * ER-resolved {@code to_asset_id} is never nulled by a prose re-parse, and a {@code basis_raw}
 * carrying ER trace notes (｜ER…) is kept as-is. Rows no longer produced by the documents are
 * removed (their source evidence disappeared). Fully re-running is idempotent: same documents
 * yield the same 413-row baseline with zero churn.
 */
@Service
public class RelationIngestionService {

    private static final Logger log = LoggerFactory.getLogger(RelationIngestionService.class);
    public static final String ORIGIN_CH2 = "逻辑FK列";
    private static final Pattern CROSS_DOMAIN = Pattern.compile("跨域\\s*(D\\d{2})");
    private static final Pattern SELF_REF_TEXT = Pattern.compile("自关联|自引用");
    /** R3 discriminator text as written in the docs, e.g. “（按 order_type）” — never inferred (R4). */
    private static final Pattern DISCRIMINATOR = Pattern.compile("[（(]\\s*[按依]\\s*([A-Za-z_][A-Za-z0-9_]*)");

    private final MetaAssetRepository assetRepo;
    private final MetaRelationRepository relRepo;

    public RelationIngestionService(MetaAssetRepository assetRepo, MetaRelationRepository relRepo) {
        this.assetRepo = assetRepo;
        this.relRepo = relRepo;
    }

    /**
     * @param edges     channel-2 edges produced by this parse of the documents
     * @param created   rows newly inserted (no identity match existed)
     * @param reused    existing rows refreshed in place, keeping their confirmation verdicts
     * @param removed   逻辑FK列 rows whose source evidence disappeared from the documents
     */
    public record RelationReport(int edges, int resolved, int unresolved, int files,
                                 int created, int reused, int removed) {
        @Override
        public String toString() {
            return "RelationReport{edges=" + edges + ", resolved=" + resolved
                    + ", unresolved=" + unresolved + ", files=" + files
                    + ", created=" + created + ", reused=" + reused + ", removed=" + removed + "}";
        }
    }

    @Transactional
    public RelationReport ingest(String erModelDir) {
        Path dir = ErModelSourceLocator.locateDir(erModelDir);
        Path modelDir = dir.resolve("03-逻辑数据模型");

        // name(lower) -> asset, for both ends of an edge
        Map<String, MetaAsset> byName = new HashMap<>();
        for (MetaAsset a : assetRepo.findAllByOrderByNameAsc()) {
            byName.put(a.getName().toLowerCase(Locale.ROOT), a);
        }

        // C-2 scope guard: only 逻辑FK列 rows are upserted; ER/manual/confirmed verdicts survive.
        Map<String, List<MetaRelation>> pool = new HashMap<>();
        for (MetaRelation r : relRepo.findAll()) {
            if (ORIGIN_CH2.equals(r.getOrigin())) {
                pool.computeIfAbsent(edgeKey(r.getFromAssetId(), r.getFromColumn(), r.getTargetRaw()),
                        k -> new ArrayList<>()).add(r);
            }
        }

        List<MetaRelation> created = new ArrayList<>();
        int edges = 0;
        int reused = 0;
        int files = 0;
        try (Stream<Path> paths = Files.list(modelDir)) {
            List<Path> docs = paths.filter(p -> p.toString().endsWith(".md")).sorted().toList();
            for (Path doc : docs) {
                files++;
                List<String> lines = Files.readAllLines(doc, StandardCharsets.UTF_8);
                List<ParsedRelation> parsed = LogicalModelRelationParser.parse(lines);
                String relPath = "03-逻辑数据模型/" + doc.getFileName();
                for (ParsedRelation pr : parsed) {
                    MetaAsset from = byName.get(pr.getFromTable().toLowerCase(Locale.ROOT));
                    if (from == null) {
                        log.warn("section table {} not found in catalog (doc {})", pr.getFromTable(), relPath);
                        continue;
                    }
                    pr.resolveTargets(pr.getFromTable(), byName.keySet());
                    String cross = null;
                    Matcher cd = CROSS_DOMAIN.matcher(pr.getBasisRaw() == null ? "" : pr.getBasisRaw());
                    if (cd.find()) {
                        cross = cd.group(1);
                    }
                    if (pr.getTargets().isEmpty()) {
                        // prose target: keep one unresolved edge for the confirmation workbench
                        edges++;
                        if (!upsert(pool, from, pr, null, cross, relPath, created)) {
                            reused++;
                        }
                    } else {
                        for (String t : pr.getTargets()) {
                            edges++;
                            if (!upsert(pool, from, pr, byName.get(t.toLowerCase(Locale.ROOT)), cross, relPath, created)) {
                                reused++;
                            }
                        }
                    }
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to scan " + modelDir, e);
        }
        relRepo.saveAll(created);

        // stale: 逻辑FK列 rows this parse no longer recognises → their doc evidence disappeared
        List<MetaRelation> stale = pool.values().stream().flatMap(List::stream).toList();
        relRepo.deleteAll(stale);
        relRepo.flush();

        // resolved/unresolved recounted from the store in the channel-2 scope, so the report
        // also reflects ER-pinned to_asset_id values that survived the reuse
        int resolved2 = 0;
        int total2 = 0;
        for (MetaRelation r : relRepo.findAll()) {
            if (ORIGIN_CH2.equals(r.getOrigin())) {
                total2++;
                if (r.getToAssetId() != null) {
                    resolved2++;
                }
            }
        }
        RelationReport report = new RelationReport(edges, resolved2, total2 - resolved2, files,
                created.size(), reused, stale.size());
        log.info("Relations upserted: {}", report);
        return report;
    }

    /**
     * Reuse-and-refresh the first pooled row for this identity, or record a new row.
     *
     * @return true when a fresh row was created, false when an existing row was reused
     */
    private boolean upsert(Map<String, List<MetaRelation>> pool, MetaAsset from, ParsedRelation pr,
                           MetaAsset to, String cross, String relPath, List<MetaRelation> created) {
        // self-reference expressed in prose (自关联) points back to the same asset
        if (to == null && SELF_REF_TEXT.matcher(pr.getTargetRaw()).find()) {
            to = from;
        }
        String key = edgeKey(from.getId(), pr.getFromColumn(), pr.getTargetRaw());
        List<MetaRelation> same = pool.get(key);
        MetaRelation reuse = (same == null || same.isEmpty()) ? null : same.remove(0);
        if (reuse == null) {
            created.add(edge(from, pr, to, cross, relPath));
            return true;
        }
        refresh(reuse, from, pr, to, cross, relPath);
        return false;
    }

    /**
     * Refresh document-derived facts on a reused row WITHOUT touching verdicts:
     * confirm_status / conflict_flag / cardinality stay; an ER-pinned to_asset_id is never
     * nulled by a prose re-parse; to_column is only added, never removed; basis_raw keeps any
     * ER trace notes (｜ER…) instead of being overwritten.
     */
    private void refresh(MetaRelation e, MetaAsset from, ParsedRelation pr, MetaAsset to,
                         String cross, String relPath) {
        e.setFromAssetId(from.getId());
        e.setFromColumn(pr.getFromColumn());
        if (to != null) {
            e.setToAssetId(to.getId());
        }
        if (e.getToColumn() == null) {
            e.setToColumn(pr.getTargetColumn());
        }
        e.setTargetRaw(pr.getTargetRaw());
        e.setEvidenceLevel(pr.evidenceLevel());
        e.setConfidence(pr.confidence());
        e.setCrossDomain(cross);
        e.setDiscriminator(discriminatorOf(pr));
        // rebuild OUR candidate entries; channel-1 (ER) entries on this row survive the refresh
        e.setCandidateTargets(Candidates.replaceSource(e.getCandidateTargets(), ORIGIN_CH2,
                ch2Candidates(pr, relPath)));
        if (e.getBasisRaw() == null || !e.getBasisRaw().contains("｜ER")) {
            e.setBasisRaw(cut(pr.getBasisRaw(), 300));   // no ER notes yet -> doc text wins
        }
        e.setSourceDoc(cut(relPath, 200));
    }

    /**
     * R3 (C-1): a polymorphic {@code FK[A/B]} cell expands into one edge per resolvable target;
     * each sibling is recorded structurally on the edge so the workbench sees the full candidate
     * set without parsing prose, immune to the basis_raw 300-char cut.
     */
    private static List<Candidate> ch2Candidates(ParsedRelation pr, String doc) {
        List<Candidate> fresh = new ArrayList<>();
        if (pr.getTargets().size() > 1) {
            for (String t : pr.getTargets()) {
                fresh.add(new Candidate(t, ORIGIN_CH2, cut(doc, 200), "多态A/B"));
            }
        }
        return fresh;
    }

    /** the discriminator column ONLY if a source document spells it out (e.g. “（按 order_type）”). */
    private static String discriminatorOf(ParsedRelation pr) {
        String hay = (pr.getTargetRaw() == null ? "" : pr.getTargetRaw())
                + " " + (pr.getBasisRaw() == null ? "" : pr.getBasisRaw());
        Matcher m = DISCRIMINATOR.matcher(hay);
        return m.find() ? m.group(1) : null;
    }

    /** identity of a channel-2 edge; stable across the channel-1 overlay (S2-3 never rewrites targetRaw). */
    private static String edgeKey(Long fromId, String fromColumn, String targetRaw) {
        return fromId + "\u0001" + (fromColumn == null ? "" : fromColumn.toLowerCase(Locale.ROOT))
                + "\u0001" + (targetRaw == null ? "" : targetRaw.trim());
    }

    private MetaRelation edge(MetaAsset from, ParsedRelation pr, MetaAsset to, String cross, String doc) {
        MetaRelation r = new MetaRelation();
        r.setFromAssetId(from.getId());
        r.setFromColumn(pr.getFromColumn());
        // self-reference expressed in prose (自关联) points back to the same asset
        if (to == null && SELF_REF_TEXT.matcher(pr.getTargetRaw()).find()) {
            to = from;
        }
        r.setToAssetId(to == null ? null : to.getId());
        r.setToColumn(pr.getTargetColumn());
        r.setTargetRaw(pr.getTargetRaw());
        r.setEvidenceLevel(pr.evidenceLevel());
        r.setConfidence(pr.confidence());
        r.setOrigin(ORIGIN_CH2);
        r.setInferred(true);
        r.setConfirmStatus("待确认");
        r.setCrossDomain(cross);
        r.setDiscriminator(discriminatorOf(pr));
        r.setCandidateTargets(Candidates.write(ch2Candidates(pr, doc)));
        r.setBasisRaw(cut(pr.getBasisRaw(), 300));
        r.setSourceDoc(cut(doc, 200));
        return r;
    }

    private static String cut(String s, int max) {
        if (s == null || s.isEmpty()) {
            return s;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}
