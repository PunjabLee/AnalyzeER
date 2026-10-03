package com.dam.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * Term &lt;-&gt; model-object reference (dam_meta.glossary_term_ref, PLAN §3.2-(3) / M3.2).
 * An M:N link binding a {@link GlossaryTerm} to an asset (table) and/or a specific column
 * — e.g. "贸易商" -> {@code jf_trader}. {@code refType} records the nature of the reference.
 *
 * <p>Uniqueness on (term, asset, column, refType) keeps repeated bindings idempotent
 * (dragging the same object onto the same term twice is a no-op).
 */
@Entity
@Table(name = "glossary_term_ref",
        indexes = @jakarta.persistence.Index(name = "idx_term_ref_term", columnList = "term_id"),
        uniqueConstraints = @UniqueConstraint(columnNames = {"term_id", "asset_id", "column_id", "ref_type"}))
public class GlossaryTermRef {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "term_id", nullable = false)
    private Long termId;

    /** bound table asset -> meta_asset.id (nullable when only a column is bound) */
    @Column(name = "asset_id")
    private Long assetId;

    /** bound column -> meta_column.id (nullable for table-level references) */
    @Column(name = "column_id")
    private Long columnId;

    /** TABLE / COLUMN (granularity of the reference) */
    @Column(name = "ref_type", nullable = false, length = 16)
    private String refType = "TABLE";

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getTermId() { return termId; }
    public void setTermId(Long termId) { this.termId = termId; }
    public Long getAssetId() { return assetId; }
    public void setAssetId(Long assetId) { this.assetId = assetId; }
    public Long getColumnId() { return columnId; }
    public void setColumnId(Long columnId) { this.columnId = columnId; }
    public String getRefType() { return refType; }
    public void setRefType(String refType) { this.refType = refType; }
}
