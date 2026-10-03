package com.dam.version;

import com.dam.domain.MetaAsset;
import com.dam.domain.MetaColumn;
import com.dam.domain.MetaVersion;
import com.dam.domain.MetaVersionItem;
import com.dam.repository.MetaAssetRepository;
import com.dam.repository.MetaColumnRepository;
import com.dam.repository.MetaVersionItemRepository;
import com.dam.repository.MetaVersionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Snapshot + diff over the asset catalog by stable {@code asset_urn} (capability M4 version mgmt,
 * PLAN §3.2-(4) / R7, adopted D3 2026-10-03).
 *
 * <p>Each snapshot freezes a canonical column-set signature per asset; the diff to the previous
 * snapshot classifies every asset ADDED / DROPPED / RETAINED / CHANGED and (for changed assets)
 * derives column-level deltas — added / dropped / type-or-nullable changed — matching
 * {@code compare_ddl_sources.ps1} semantics and independent of row order (评审#3).
 */
@Service
public class VersionService {

    private final MetaAssetRepository assetRepo;
    private final MetaColumnRepository columnRepo;
    private final MetaVersionRepository versionRepo;
    private final MetaVersionItemRepository itemRepo;

    private static final Logger log = LoggerFactory.getLogger(VersionService.class);

    /**
     * Signature-algorithm tag frozen on every {@link MetaVersion}. Bumped whenever the canonical
     * {@link #signatureOf} definition changes, so an in-place upgrade can detect a stale baseline
     * whose stored hashes are not comparable and re-pin a FULL baseline instead of diffing against
     * it and emitting mass phantom CHANGED (评审 S2-1). 1/null = legacy ordinal order, 2 = sorted set.
     */
    private static final Integer CURRENT_SIG_ALGO = 2;

    /** the meta_version.version_no unique-key name; must match the entity's @UniqueConstraint. */
    private static final String VERSION_NO_UK = "uk_meta_version_no";

    /**
     * Self reference so {@link #createSnapshot} can drive the transactional worker through the
     * proxy and retry on a {@code version_no} collision (S3-1); {@code @Lazy} breaks the cycle.
     */
    @Lazy
    @Autowired
    private VersionService self;

    public VersionService(MetaAssetRepository assetRepo,
                          MetaColumnRepository columnRepo,
                          MetaVersionRepository versionRepo,
                          MetaVersionItemRepository itemRepo) {
        this.assetRepo = assetRepo;
        this.columnRepo = columnRepo;
        this.versionRepo = versionRepo;
        this.itemRepo = itemRepo;
    }

    /**
     * Capture a snapshot of the current catalog and diff it against the previous snapshot.
     * The version number is assigned from the current maximum; concurrent callers can momentarily
     * compute the same value, which the {@code version_no} unique constraint then rejects — so we
     * retry (each attempt is its own transaction via the {@code self} proxy).
     */
    public MetaVersion createSnapshot(String note) {
        int maxAttempts = 5;
        for (int attempt = 1; ; attempt++) {
            try {
                return self.doSnapshot(note);
            } catch (DataIntegrityViolationException dive) {
                // Only a version_no collision is retryable; any other integrity problem (e.g. an
                // over-long asset_urn) would just repeat, so surface it immediately (评审 S3-1).
                if (attempt >= maxAttempts || !isVersionNoCollision(dive)) {
                    throw dive;
                }
                log.warn("version_no collision while snapshotting (attempt {}/{}), retrying: {}",
                        attempt, maxAttempts, dive.getMostSpecificCause().getMessage());
                sleepBackoff(attempt);
            }
        }
    }

    private static boolean isVersionNoCollision(DataIntegrityViolationException dive) {
        for (Throwable t = dive; t != null; t = t.getCause()) {
            String msg = t.getMessage();
            if (msg != null) {
                String upper = msg.toUpperCase();
                if (upper.contains(VERSION_NO_UK.toUpperCase()) || upper.contains("VERSION_NO")) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void sleepBackoff(int attempt) {
        try {
            Thread.sleep(20L * attempt);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * The atomic snapshot + diff. Runs in a NEW transaction ({@link Propagation#REQUIRES_NEW}) so
     * each retry of {@link #createSnapshot} is genuinely independent and a collision fully rolls
     * back (评审 S3-2); invoke only through the proxy (via {@code self} / {@code createSnapshot}).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public MetaVersion doSnapshot(String note) {
        Optional<MetaVersion> prev = versionRepo.findTopByOrderByVersionNoDesc();
        // A snapshot captured under a different signature algorithm (older CURRENT_SIG_ALGO, or a
        // pre-tag legacy row stored as null) freezes incomparable hashes: treat it as "no baseline"
        // and re-pin FULL rather than diff against it (which would mark nearly every asset CHANGED
        // with empty deltas) (评审 S2-1).
        boolean comparableBaseline = prev.isPresent() && CURRENT_SIG_ALGO.equals(prev.get().getSignatureAlgo());
        Map<String, MetaVersionItem> prevItems = comparableBaseline
                ? baselineItems(prev.get())
                : Map.of();

        MetaVersion version = new MetaVersion();
        version.setVersionNo(prev.map(v -> v.getVersionNo() + 1).orElse(1));
        version.setBaselineType(comparableBaseline ? "INCREMENT" : "FULL");
        version.setSnapshotAt(Instant.now());
        version.setNote(note);
        version.setSignatureAlgo(CURRENT_SIG_ALGO);

        List<MetaAsset> assets = assetRepo.findAllByOrderByNameAsc();
        version.setAssetCount(assets.size());
        MetaVersion savedVersion = versionRepo.save(version);

        List<MetaVersionItem> batch = new ArrayList<>();
        for (MetaAsset a : assets) {
            String signature = signatureOf(a.getId());
            String hash = sha256(signature);
            MetaVersionItem prior = prevItems.get(a.getAssetUrn());
            String changeType = prior == null ? "ADDED"
                    : (prior.getSchemaHash().equals(hash) ? "RETAINED" : "CHANGED");
            batch.add(item(savedVersion.getId(), a.getAssetUrn(), a.getName(), changeType, signature, hash));
        }
        // dropped: present in previous snapshot but gone now
        Map<String, MetaAsset> currentByUrn = new LinkedHashMap<>();
        for (MetaAsset a : assets) {
            currentByUrn.put(a.getAssetUrn(), a);
        }
        for (MetaVersionItem prior : prevItems.values()) {
            if (!currentByUrn.containsKey(prior.getAssetUrn())) {
                batch.add(item(savedVersion.getId(), prior.getAssetUrn(), prior.getAssetName(),
                        "DROPPED", prior.getSignature(), prior.getSchemaHash()));
            }
        }

        itemRepo.saveAll(batch);
        return savedVersion;
    }

    /** Per-changeType counts of a snapshot's items. */
    public Map<String, Long> summary(Long versionId) {
        Map<String, Long> counts = new TreeMap<>();
        for (MetaVersionItem it : itemRepo.findByVersionId(versionId)) {
            counts.merge(it.getChangeType(), 1L, Long::sum);
        }
        return counts;
    }

    /**
     * Column-level deltas of the assets marked CHANGED in {@code versionId}, computed by
     * comparing against the immediately previous snapshot's stored signatures.
     */
    public List<ChangedAsset> changedAssets(Long versionId) {
        MetaVersion cur = versionRepo.findById(versionId).orElseThrow();
        Optional<MetaVersion> prev = versionRepo.findByVersionNo(cur.getVersionNo() - 1);
        // Diff only against a comparable predecessor (same signature algorithm); a re-pin boundary
        // or a legacy snapshot has no meaningful per-column delta to report (评审 S2-1 / S3-4).
        Map<String, String> prevSig = (prev.isPresent() && CURRENT_SIG_ALGO.equals(prev.get().getSignatureAlgo()))
                ? signatures(new ArrayList<>(baselineItems(prev.get()).values()))
                : Map.of();

        List<ChangedAsset> out = new ArrayList<>();
        for (MetaVersionItem it : itemRepo.findByVersionIdAndChangeType(versionId, "CHANGED")) {
            out.add(delta(it, prevSig.getOrDefault(it.getAssetUrn(), "")));
        }
        return out;
    }

    /** The previous snapshot's live entries: its items minus DROPPED tombstones (评审 S2-1 / S3-4). */
    private Map<String, MetaVersionItem> baselineItems(MetaVersion prev) {
        return indexByUrn(itemRepo.findByVersionId(prev.getId()).stream()
                .filter(it -> !"DROPPED".equals(it.getChangeType()))
                .toList());
    }

    // ---- signature & delta helpers ---------------------------------------------------------

    private MetaVersionItem item(Long versionId, String urn, String name, String changeType,
                                 String signature, String hash) {
        MetaVersionItem it = new MetaVersionItem();
        it.setVersionId(versionId);
        it.setAssetUrn(urn);
        it.setAssetName(name);
        it.setChangeType(changeType);
        it.setSignature(signature);
        it.setSchemaHash(hash);
        return it;
    }

    /**
     * canonical column-set fingerprint: each column as "name|TYPE|NULLABLE", sorted by that string
     * and joined. Sorting (set semantics) rather than physical ordinal order means a pure drag
     * reorder does not change the signature, so it is not mis-reported as CHANGED (S3-2);
     * consistent with the class/README "independent of row order" contract.
     */
    private String signatureOf(Long assetId) {
        List<String> parts = new ArrayList<>();
        for (MetaColumn c : columnRepo.findByAssetIdOrderByOrdinalAsc(assetId)) {
            parts.add((c.getName() == null ? "" : c.getName().trim())
                    + '|'
                    + (c.getType() == null ? "" : c.getType().trim().toUpperCase())
                    + '|'
                    + (c.getNullable() == null ? "" : c.getNullable().trim().toUpperCase()));
        }
        Collections.sort(parts);
        return String.join("\u0001", parts);
    }

    private static Map<String, MetaVersionItem> indexByUrn(List<MetaVersionItem> items) {
        Map<String, MetaVersionItem> m = new LinkedHashMap<>();
        for (MetaVersionItem it : items) {
            m.put(it.getAssetUrn(), it);
        }
        return m;
    }

    private static Map<String, String> signatures(List<MetaVersionItem> items) {
        Map<String, String> m = new LinkedHashMap<>();
        for (MetaVersionItem it : items) {
            m.put(it.getAssetUrn(), it.getSignature());
        }
        return m;
    }

    /** parse a signature into an ordered name -> "TYPE|NULLABLE" map */
    private static Map<String, String> parse(String signature) {
        Map<String, String> map = new LinkedHashMap<>();
        if (signature == null || signature.isEmpty()) {
            return map;
        }
        for (String part : signature.split("\u0001")) {
            int bar = part.indexOf('|');
            String name = bar < 0 ? part : part.substring(0, bar);
            String def = bar < 0 ? "" : part.substring(bar + 1);
            map.put(name, def);
        }
        return map;
    }

    private static ChangedAsset delta(MetaVersionItem cur, String prevSignature) {
        Map<String, String> before = parse(prevSignature);
        Map<String, String> after = parse(cur.getSignature());
        List<String> added = new ArrayList<>();
        List<String> dropped = new ArrayList<>();
        List<String> changed = new ArrayList<>();
        for (Map.Entry<String, String> e : after.entrySet()) {
            String old = before.get(e.getKey());
            if (old == null) {
                added.add(e.getKey());
            } else if (!old.equals(e.getValue())) {
                changed.add(e.getKey() + ": " + old + " → " + e.getValue());
            }
        }
        for (String name : before.keySet()) {
            if (!after.containsKey(name)) {
                dropped.add(name);
            }
        }
        return new ChangedAsset(cur.getAssetUrn(), cur.getAssetName(), added, dropped, changed);
    }

    private static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("sha-256 unavailable", e);
        }
    }

    /** column-level delta view for a CHANGED asset between two adjacent snapshots */
    public record ChangedAsset(String assetUrn, String assetName, List<String> addedColumns,
                               List<String> droppedColumns, List<String> changedColumns) { }
}
