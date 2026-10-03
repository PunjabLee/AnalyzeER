package com.dam.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * Physical Data Model node (dam_meta.model_pdm, PLAN §3.2-(4) / capability M4.3).
 *
 * <p>The physical layer — a thin model node wrapping a real {@link MetaAsset} parsed verbatim from
 * {@code test_erp.sql} DDL. It carries the stable {@code assetUrn} and the physical column count so
 * the three-level view can drill from BOM→LDM down to the concrete table structure.
 */
@Entity
@Table(name = "model_pdm", uniqueConstraints = @UniqueConstraint(columnNames = "name"))
public class ModelPdm {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(name = "asset_id", nullable = false)
    private Long assetId;

    @Column(name = "asset_urn", length = 300)
    private String assetUrn;

    @Column(name = "column_count")
    private Integer columnCount;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public Long getAssetId() { return assetId; }
    public void setAssetId(Long assetId) { this.assetId = assetId; }
    public String getAssetUrn() { return assetUrn; }
    public void setAssetUrn(String assetUrn) { this.assetUrn = assetUrn; }
    public Integer getColumnCount() { return columnCount; }
    public void setColumnCount(Integer columnCount) { this.columnCount = columnCount; }
}
