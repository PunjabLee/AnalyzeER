package com.dam.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

/**
 * One asset's entry inside a {@link MetaVersion} snapshot (dam_meta.meta_version_item,
 * PLAN §3.2-(4) / R7). Keyed by stable {@code assetUrn}; {@code changeType} is the diff
 * versus the immediately previous snapshot: ADDED / DROPPED / RETAINED / CHANGED
 * (compare_ddl_sources.ps1 semantics, not row-order dependent — 评审#3).
 *
 * <p>{@code signature} is the canonical column-set fingerprint ("name:TYPE:nullable" per
 * column, ordinal-ordered); {@code schemaHash} is its SHA-256 for O(1) change detection.
 * Column-level deltas (added/dropped/type-changed) are derived on read by comparing the
 * signatures of adjacent snapshots, so no redundant delta table is needed.
 */
@Entity
@Table(name = "meta_version_item")
public class MetaVersionItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "version_id", nullable = false)
    private Long versionId;

    @Column(name = "asset_urn", nullable = false, length = 300)
    private String assetUrn;

    @Column(name = "asset_name", length = 200)
    private String assetName;

    /** ADDED / DROPPED / RETAINED / CHANGED (vs previous snapshot) */
    @Column(name = "change_type", nullable = false, length = 16)
    private String changeType;

    /**
     * canonical column-set fingerprint of the asset at this snapshot. Explicit {@code longtext}:
     * a bare {@code @Lob String} is created as TINYTEXT (255B) by the MySQL dialect, which truncates
     * wide tables' signatures (data-too-long); longtext removes any size ceiling.
     */
    @Lob
    @Column(name = "signature", nullable = false, columnDefinition = "longtext")
    private String signature;

    @Column(name = "schema_hash", nullable = false, length = 64)
    private String schemaHash;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getVersionId() { return versionId; }
    public void setVersionId(Long versionId) { this.versionId = versionId; }
    public String getAssetUrn() { return assetUrn; }
    public void setAssetUrn(String assetUrn) { this.assetUrn = assetUrn; }
    public String getAssetName() { return assetName; }
    public void setAssetName(String assetName) { this.assetName = assetName; }
    public String getChangeType() { return changeType; }
    public void setChangeType(String changeType) { this.changeType = changeType; }
    public String getSignature() { return signature; }
    public void setSignature(String signature) { this.signature = signature; }
    public String getSchemaHash() { return schemaHash; }
    public void setSchemaHash(String schemaHash) { this.schemaHash = schemaHash; }
}
