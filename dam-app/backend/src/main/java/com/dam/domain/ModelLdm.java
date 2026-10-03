package com.dam.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * Logical Data Model entity node (dam_meta.model_ldm, PLAN §3.2-(4) / capability M4.2).
 *
 * <p>A logical entity — one per real table named by a {@link ModelBom} row after name-verification
 * against the physical catalog. {@code assetId} points at the backing {@link MetaAsset}; the LDM
 * is the business-facing view (name + owning domain + meaning) sitting between the coarse BOM and
 * the physical PDM.
 */
@Entity
@Table(name = "model_ldm", uniqueConstraints = @UniqueConstraint(columnNames = "name"))
public class ModelLdm {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(name = "domain_code", length = 16)
    private String domainCode;

    @Column(length = 1000)
    private String description;

    /** backing physical asset id (meta_asset.id); null only if the name was never resolvable */
    @Column(name = "asset_id")
    private Long assetId;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDomainCode() { return domainCode; }
    public void setDomainCode(String domainCode) { this.domainCode = domainCode; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public Long getAssetId() { return assetId; }
    public void setAssetId(Long assetId) { this.assetId = assetId; }
}
