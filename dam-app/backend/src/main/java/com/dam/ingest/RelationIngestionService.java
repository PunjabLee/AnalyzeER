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
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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
    /** the human verdict that ingestion must never destroy (review N-3 quarantine trigger). */
    public static final String VERDICT_CONFIRMED = "已确认";
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
     * @param removed   逻辑FK列 rows whose source evidence disappeared AND that carried no 已确认
     *                  verdict (physical delete)
     * @param danglingRemoved edges (ANY origin) whose endpoints no longer resolve in the catalog
     *                  and carried no 已确认 verdict (physical delete)
     * @param staleKept       evidence vanished but verdict is 已确认 → quarantined, NOT deleted
     *                  (conflict_flag raised + trace note; workbench re-review required)
     * @param danglingKept    endpoint unresolvable but verdict is 已确认 → quarantined likewise
     */
    public record RelationReport(int edges, int resolved, int unresolved, int files,
                                 int created, int reused, int removed, int danglingRemoved,
                                 int staleKept, int danglingKept) {
        @Override
        public String toString() {
            return "RelationReport{edges=" + edges + ", resolved=" + resolved
                    + ", unresolved=" + unresolved + ", files=" + files
                    + ", created=" + created + ", reused=" + reused + ", removed=" + removed
                    + ", danglingRemoved=" + danglingRemoved
                    + ", staleKept=" + staleKept + ", danglingKept=" + danglingKept + "}";
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
        // Identity is NATURAL (review N-2): table NAME + column + document target text — surrogate
        // ids never enter the key, so asset renumbering can no longer orphan pooled rows.
        // Polymorphic siblings are NOT keyed by target (① legitimately re-pins to_asset_id on prose
        // rows; a keyed target would break that symmetry) — see pick(): verdict-aware matching.
        Map<Long, String> nameById = new HashMap<>();
        byName.forEach((k, a) -> nameById.put(a.getId(), a.getName().toLowerCase(Locale.ROOT)));
        Map<String, List<MetaRelation>> pool = new HashMap<>();
        for (MetaRelation r : relRepo.findAll(org.springframework.data.domain.Sort.by("id"))) {
            if (!ORIGIN_CH2.equals(r.getOrigin())) {
                continue;
            }
            String fromName = r.getFromAssetId() == null ? null : nameById.get(r.getFromAssetId());
            if (fromName == null) {
                continue;   // unresolvable endpoint: not poolable, the dangling sweep below owns it
            }
            pool.computeIfAbsent(edgeKey(fromName, r.getFromColumn(), r.getTargetRaw()),
                    k -> new ArrayList<>()).add(r);
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

        // stale: 逻辑FK列 rows this parse no longer recognises → their doc evidence disappeared.
        // Verdict-aware (review N-3): 已确认 rows are NOT physically deleted — evidence vanishing
        // must not silently annul a human decision; they are quarantined (conflict_flag + trace
        // note) for the confirmation workbench instead.
        List<MetaRelation> stale = pool.values().stream().flatMap(List::stream).toList();
        List<MetaRelation> staleDelete = new ArrayList<>();
        int staleKept = 0;
        for (MetaRelation r : stale) {
            if (VERDICT_CONFIRMED.equals(r.getConfirmStatus())) {
                quarantine(r, "｜文档证据消失待复核");
                staleKept++;
            } else {
                staleDelete.add(r);
            }
        }
        relRepo.deleteAll(staleDelete);
        relRepo.flush();

        // hygiene sweep: edges whose endpoints do not resolve in the catalog are garbage — with
        // pinned ids (N-1 fix) this only happens when a table genuinely left the document, and an
        // 已确认 row in that state is quarantined for review rather than destroyed (same rule as stale).
        Set<Long> liveAssetIds = new HashSet<>();
        byName.values().forEach(a -> liveAssetIds.add(a.getId()));
        List<MetaRelation> dangling = new ArrayList<>();
        for (MetaRelation r : relRepo.findAll()) {
            boolean fromGone = r.getFromAssetId() == null || !liveAssetIds.contains(r.getFromAssetId());
            boolean toGone = r.getToAssetId() != null && !liveAssetIds.contains(r.getToAssetId());
            if (fromGone || toGone) {
                dangling.add(r);
            }
        }
        List<MetaRelation> danglingDelete = new ArrayList<>();
        int danglingKept = 0;
        for (MetaRelation r : dangling) {
            if (VERDICT_CONFIRMED.equals(r.getConfirmStatus())) {
                quarantine(r, "｜端点已不在目录待复核");
                danglingKept++;
            } else {
                danglingDelete.add(r);
            }
        }
        relRepo.deleteAll(danglingDelete);
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
                created.size(), reused, staleDelete.size(), danglingDelete.size(),
                staleKept, danglingKept);
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
        String key = edgeKey(from.getName(), pr.getFromColumn(), pr.getTargetRaw());
        List<MetaRelation> same = pool.get(key);
        MetaRelation reuse = pick(same, to);
        if (reuse == null) {
            created.add(edge(from, pr, to, cross, relPath));
            return true;
        }
        refresh(reuse, from, pr, to, cross, relPath);
        return false;
    }

    /**
     * Target-aware pairing of a parsed sibling with a pooled row of the same (from, column,
     * target-text) identity (review N-3: no blind {@code remove(0)}):
     * <ul>
     *   <li>parsed with a concrete target → prefer the pooled row ALREADY pointing there; else an
     *       unpinned (prose-stage) row may adopt it; if every pooled row points elsewhere the
     *       document retargeted — never reassign a foreign verdict silently, so create fresh and
     *       let the mismatched rows fall to the verdict-aware stale sweep;</li>
     *   <li>parsed as prose (no target) → prefer an unpinned row, otherwise take the first: a
     *       channel-1-pinned {@code to_asset_id} is legitimate state that refresh never nulls.</li>
     * </ul>
     */
    private static MetaRelation pick(List<MetaRelation> same, MetaAsset to) {
        if (same == null || same.isEmpty()) {
            return null;
        }
        if (to != null) {
            for (int i = 0; i < same.size(); i++) {
                if (to.getId().equals(same.get(i).getToAssetId())) {
                    return same.remove(i);
                }
            }
        }
        for (int i = 0; i < same.size(); i++) {
            if (same.get(i).getToAssetId() == null) {
                return same.remove(i);
            }
        }
        return to == null ? same.remove(0) : null;
    }

    /**
     * Refresh document-derived facts on a reused row WITHOUT touching verdicts:
     * confirm_status / conflict_flag / cardinality stay; an ER-pinned to_asset_id is never
     * nulled by a prose re-parse; to_column is only added, never removed. {@code basis_raw} is
     * now ALWAYS refreshed from the current document (review N-4: ingestion trace no longer lives
     * here, so nothing can freeze the accepted M1 basis text anymore).
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
        e.setDiscriminator(extractDiscriminator(
                (pr.getTargetRaw() == null ? "" : pr.getTargetRaw())
                        + " " + (pr.getBasisRaw() == null ? "" : pr.getBasisRaw())));
        // rebuild OUR candidate entries; channel-1 (ER) entries on this row survive the refresh
        e.setCandidateTargets(Candidates.replaceSource(e.getCandidateTargets(), ORIGIN_CH2,
                ch2Candidates(pr, relPath)));
        e.setBasisRaw(cut(pr.getBasisRaw(), 300));   // pure document text (N-4: no ER note can freeze it)
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

    /**
     * The discriminator column ONLY if a source document spells it out (e.g. “（按 order_type）”)
     * — never inferred (R4). Package-visible for the R3 positive/negative regression (review N-9).
     */
    static String extractDiscriminator(String haystack) {
        Matcher m = DISCRIMINATOR.matcher(haystack == null ? "" : haystack);
        return m.find() ? m.group(1) : null;
    }

    /**
     * Identity of a channel-2 edge — NATURAL keys only (review N-2): the table NAME (never a
     * surrogate id, so asset renumbering cannot orphan a pooled row) + FK column + the document's
     * target text (never rewritten by the channel-1 overlay, S2-3). Polymorphic sibling drift is
     * handled by {@link #pick}, NOT by keying on the mutable {@code to_asset_id}.
     */
    private static String edgeKey(String fromName, String fromColumn, String targetRaw) {
        return (fromName == null ? "" : fromName.toLowerCase(Locale.ROOT))
                + "\u0001" + (fromColumn == null ? "" : fromColumn.toLowerCase(Locale.ROOT))
                + "\u0001" + (targetRaw == null ? "" : targetRaw.trim());
    }

    /** Verdict-preserving quarantine: keep the row, flag it, record the re-review trace (N-4: in ingest_trace). */
    private void quarantine(MetaRelation r, String traceNote) {
        r.setConflictFlag(true);
        String trace = r.getIngestTrace() == null ? "" : r.getIngestTrace();
        if (!trace.contains(traceNote)) {
            r.setIngestTrace(trace + traceNote);
        }
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
        r.setDiscriminator(extractDiscriminator(
                (pr.getTargetRaw() == null ? "" : pr.getTargetRaw())
                        + " " + (pr.getBasisRaw() == null ? "" : pr.getBasisRaw())));
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
