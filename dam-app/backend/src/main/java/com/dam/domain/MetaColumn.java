package com.dam.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

/**
 * Physical column (dam_meta.meta_column). M0 subset.
 * Columns are stored expanded to atomic physical columns (review #1),
 * so column_count per asset equals the DDL column count (e.g. jf_sales_order = 90).
 */
@Entity
@Table(name = "meta_column", indexes = @Index(name = "idx_col_asset", columnList = "asset_id"))
public class MetaColumn {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "asset_id", nullable = false)
    private Long assetId;

    @Column(nullable = false)
    private Integer ordinal;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(length = 200)
    private String type;

    /** N = NOT NULL, Y = nullable */
    @Column(length = 1)
    private String nullable;

    @Column(name = "default_val", length = 200)
    private String defaultVal;

    /** PK / UK / FK / AUTO / empty */
    @Column(name = "key_hint", length = 16)
    private String keyHint;

    @Column(length = 1000)
    private String meaning;

    /** physical | logical-merged (M0 always physical; merged-row handling added later) */
    @Column(name = "source_layer", length = 16)
    private String sourceLayer;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getAssetId() { return assetId; }
    public void setAssetId(Long assetId) { this.assetId = assetId; }
    public Integer getOrdinal() { return ordinal; }
    public void setOrdinal(Integer ordinal) { this.ordinal = ordinal; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getNullable() { return nullable; }
    public void setNullable(String nullable) { this.nullable = nullable; }
    public String getDefaultVal() { return defaultVal; }
    public void setDefaultVal(String defaultVal) { this.defaultVal = defaultVal; }
    public String getKeyHint() { return keyHint; }
    public void setKeyHint(String keyHint) { this.keyHint = keyHint; }
    public String getMeaning() { return meaning; }
    public void setMeaning(String meaning) { this.meaning = meaning; }
    public String getSourceLayer() { return sourceLayer; }
    public void setSourceLayer(String sourceLayer) { this.sourceLayer = sourceLayer; }
}
