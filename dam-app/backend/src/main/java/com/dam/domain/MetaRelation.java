package com.dam.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

/**
 * Inferred relation edge (dam_meta.meta_relation, PLAN §3.2).
 * This DB has ZERO physical foreign keys, so every edge is inferred from the er-model
 * documents (origin: logical FK column / ER evidence / manual) and starts as 待确认.
 */
@Entity
@Table(name = "meta_relation", indexes = {
        @Index(name = "idx_rel_from", columnList = "from_asset_id"),
        @Index(name = "idx_rel_to", columnList = "to_asset_id")
})
public class MetaRelation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "from_asset_id", nullable = false)
    private Long fromAssetId;

    @Column(name = "from_column", nullable = false, length = 200)
    private String fromColumn;

    /** resolved target asset; null when the doc names the target only in prose (e.g. 库存明细) */
    @Column(name = "to_asset_id")
    private Long toAssetId;

    @Column(name = "to_column", length = 200)
    private String toColumn;

    /** raw target text inside FK[...] as written in the logical model */
    @Column(name = "target_raw", length = 200)
    private String targetRaw;

    /** 注释明示 / 索引佐证 / 字段命名 / 业务语义推断 / 待确认 (five-level evidence, er-model §六) */
    @Column(name = "evidence_level", nullable = false, length = 32)
    private String evidenceLevel;

    /** ER证据摘录 / 逻辑FK列 / 手工录入 */
    @Column(name = "origin", nullable = false, length = 32)
    private String origin;

    @Column(name = "is_inferred", nullable = false)
    private boolean inferred = true;

    @Column(name = "confidence", nullable = false)
    private double confidence;

    /** 待确认 / 已确认 / 驳回 (M5 confirmation loop) */
    @Column(name = "confirm_status", nullable = false, length = 16)
    private String confirmStatus = "待确认";

    @Column(name = "cross_domain", length = 32)
    private String crossDomain;

    /** 1:1 / 1:N (channel-1 ER evidence uniquely supplies cardinality; null until overlaid) */
    @Column(name = "cardinality", length = 16)
    private String cardinality;

    /** 依据原文 as written after the target inside FK[...] (minus the · separators) */
    @Column(name = "basis_raw", length = 300)
    private String basisRaw;

    /** source document, e.g. 03-逻辑数据模型/D01-销售订单域.md */
    @Column(name = "source_doc", length = 200)
    private String sourceDoc;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getFromAssetId() { return fromAssetId; }
    public void setFromAssetId(Long fromAssetId) { this.fromAssetId = fromAssetId; }
    public String getFromColumn() { return fromColumn; }
    public void setFromColumn(String fromColumn) { this.fromColumn = fromColumn; }
    public Long getToAssetId() { return toAssetId; }
    public void setToAssetId(Long toAssetId) { this.toAssetId = toAssetId; }
    public String getToColumn() { return toColumn; }
    public void setToColumn(String toColumn) { this.toColumn = toColumn; }
    public String getTargetRaw() { return targetRaw; }
    public void setTargetRaw(String targetRaw) { this.targetRaw = targetRaw; }
    public String getEvidenceLevel() { return evidenceLevel; }
    public void setEvidenceLevel(String evidenceLevel) { this.evidenceLevel = evidenceLevel; }
    public String getOrigin() { return origin; }
    public void setOrigin(String origin) { this.origin = origin; }
    public boolean isInferred() { return inferred; }
    public void setInferred(boolean inferred) { this.inferred = inferred; }
    public double getConfidence() { return confidence; }
    public void setConfidence(double confidence) { this.confidence = confidence; }
    public String getConfirmStatus() { return confirmStatus; }
    public void setConfirmStatus(String confirmStatus) { this.confirmStatus = confirmStatus; }
    public String getCrossDomain() { return crossDomain; }
    public void setCrossDomain(String crossDomain) { this.crossDomain = crossDomain; }
    public String getCardinality() { return cardinality; }
    public void setCardinality(String cardinality) { this.cardinality = cardinality; }
    public String getBasisRaw() { return basisRaw; }
    public void setBasisRaw(String basisRaw) { this.basisRaw = basisRaw; }
    public String getSourceDoc() { return sourceDoc; }
    public void setSourceDoc(String sourceDoc) { this.sourceDoc = sourceDoc; }
}
