package com.dam.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Version snapshot header (dam_meta.meta_version, PLAN §3.2-(4) / R7, adopted D3 2026-10-03).
 * A snapshot freezes the column-set signature of every asset at capture time and, against the
 * previous snapshot, classifies each asset as added/dropped/retained/changed (by stable
 * {@code asset_urn}, not row order — 评审#3). The first snapshot of an empty store is a
 * FULL baseline; later ones are INCREMENT.
 */
@Entity
@Table(name = "meta_version")
public class MetaVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** monotonic 1-based version number; unique so concurrent snapshots collide on insert
     *  and {@link com.dam.version.VersionService} retries rather than producing duplicate numbers */
    @Column(name = "version_no", nullable = false, unique = true)
    private Integer versionNo;

    /** FULL (first baseline) / INCREMENT */
    @Column(name = "baseline_type", nullable = false, length = 16)
    private String baselineType;

    @Column(name = "snapshot_at", nullable = false)
    private Instant snapshotAt;

    @Column(name = "asset_count", nullable = false)
    private Integer assetCount;

    /** source this snapshot came from -> meta_source.id (nullable) */
    @Column(name = "source_id")
    private Long sourceId;

    @Column(length = 500)
    private String note;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Integer getVersionNo() { return versionNo; }
    public void setVersionNo(Integer versionNo) { this.versionNo = versionNo; }
    public String getBaselineType() { return baselineType; }
    public void setBaselineType(String baselineType) { this.baselineType = baselineType; }
    public Instant getSnapshotAt() { return snapshotAt; }
    public void setSnapshotAt(Instant snapshotAt) { this.snapshotAt = snapshotAt; }
    public Integer getAssetCount() { return assetCount; }
    public void setAssetCount(Integer assetCount) { this.assetCount = assetCount; }
    public Long getSourceId() { return sourceId; }
    public void setSourceId(Long sourceId) { this.sourceId = sourceId; }
    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
}
