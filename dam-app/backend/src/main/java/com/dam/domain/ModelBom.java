package com.dam.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

/**
 * Business Object Model node (dam_meta.model_bom, PLAN §3.2-(4) / capability M4.1).
 *
 * <p>Coarse-grained business object sourced verbatim from the authoritative三分类 of
 * {@code er-model/05-跨域核心关系总览.md §二} (Master Data / Transactional / Config & Dictionary).
 * This is a documented structural classification — not invented business semantics (R4) — so it
 * is safe to install as fact. Each BOM links down to logical/physical entities via
 * {@link ModelMapping}.
 */
@Entity
@Table(name = "model_bom", indexes = @Index(name = "idx_bom_category", columnList = "category"))
public class ModelBom {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** primary identifier (first table token, or the raw expression when none matched) */
    @Column(nullable = false, length = 200)
    private String name;

    /** human display label, e.g. the Chinese name in parentheses (贸易商) */
    @Column(length = 200)
    private String label;

    /** MASTER / TRANSACTIONAL / CONFIG (from the §二 sub-section) */
    @Column(nullable = false, length = 16)
    private String category;

    @Column(name = "domain_code", length = 16)
    private String domainCode;

    @Column(length = 1000)
    private String description;

    /** the raw 实体 cell text as written in 05 §二 (kept so shorthand is never lost) */
    @Column(name = "source_expr", length = 500)
    private String sourceExpr;

    /** provenance marker */
    @Column(length = 64)
    private String source = "05-§2 三分类";

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public String getDomainCode() { return domainCode; }
    public void setDomainCode(String domainCode) { this.domainCode = domainCode; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getSourceExpr() { return sourceExpr; }
    public void setSourceExpr(String sourceExpr) { this.sourceExpr = sourceExpr; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
}
