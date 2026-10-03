package com.dam.version;

import com.dam.domain.MetaAsset;
import com.dam.domain.MetaColumn;
import com.dam.domain.MetaVersion;
import com.dam.domain.MetaVersionItem;
import com.dam.repository.MetaAssetRepository;
import com.dam.repository.MetaColumnRepository;
import com.dam.repository.MetaVersionItemRepository;
import com.dam.repository.MetaVersionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
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

    public VersionService(MetaAssetRepository assetRepo,
                          MetaColumnRepository columnRepo,
                          MetaVersionRepository versionRepo,
                          MetaVersionItemRepository itemRepo) {
        this.assetRepo = assetRepo;
        this.columnRepo = columnRepo;
        this.versionRepo = versionRepo;
        this.itemRepo = itemRepo;
    }

    /** Capture a snapshot of the current catalog and diff it against the previous snapshot. */
    @Transactional
    public MetaVersion createSnapshot(String note) {
        Optional<MetaVersion> prev = versionRepo.findTopByOrderByVersionNoDesc();
        Map<String, MetaVersionItem> prevItems = prev
                .map(v -> indexByUrn(itemRepo.findByVersionId(v.getId())))
                .orElseGet(Map::of);

        MetaVersion version = new MetaVersion();
        version.setVersionNo(prev.map(v -> v.getVersionNo() + 1).orElse(1));
        version.setBaselineType(prev.isEmpty() ? "FULL" : "INCREMENT");
        version.setSnapshotAt(Instant.now());
        version.setNote(note);

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
        Map<String, String> prevSig = prev.isPresent()
                ? signatures(itemRepo.findByVersionId(prev.get().getId()))
                : Map.of();

        List<ChangedAsset> out = new ArrayList<>();
        for (MetaVersionItem it : itemRepo.findByVersionIdAndChangeType(versionId, "CHANGED")) {
            out.add(delta(it, prevSig.getOrDefault(it.getAssetUrn(), "")));
        }
        return out;
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

    /** canonical per-column fingerprint: "name|TYPE|NULLABLE" joined, ordinal-ordered */
    private String signatureOf(Long assetId) {
        StringBuilder sb = new StringBuilder();
        for (MetaColumn c : columnRepo.findByAssetIdOrderByOrdinalAsc(assetId)) {
            if (sb.length() > 0) {
                sb.append('\u0001');
            }
            sb.append(c.getName() == null ? "" : c.getName().trim())
                    .append('|')
                    .append(c.getType() == null ? "" : c.getType().trim().toUpperCase())
                    .append('|')
                    .append(c.getNullable() == null ? "" : c.getNullable().trim().toUpperCase());
        }
        return sb.toString();
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
