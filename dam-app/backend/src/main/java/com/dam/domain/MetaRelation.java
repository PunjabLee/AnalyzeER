package com.dam.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
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

    /**
     * A filterable "needs human disambiguation" flag for the confirmation workbench — kept separate
     * from confirm_status so the 待确认/已确认/驳回 loop stays uncontaminated. It is set by ANY of
     * three reviewers (review N-12: the flag is overloaded BY DESIGN, the origin is told apart by
     * {@link #ingestTrace} / {@link #candidateTargets}, not by this boolean alone):
     * <ol>
     *   <li>channel-1 ER evidence points this (from,col) edge at a DIFFERENT concrete target than
     *       channel-2 already did (S3-1, {@code markConflict});</li>
     *   <li>an ER prose edge has &gt;1 parent candidate for its (child,fk) position (R3 multi-parent,
     *       {@code resolveProse});</li>
     *   <li>an ACCEPTED (已确认) edge was quarantined because its document evidence vanished or an
     *       endpoint left the catalog — verdict kept, flagged for re-review (N-3, {@code quarantine}).</li>
     * </ol>
     */
    @Column(name = "conflict_flag", nullable = false)
    private boolean conflictFlag = false;

    /** 依据原文 as written after the target inside FK[...] (minus the · separators). DOCUMENT TEXT ONLY
     *  (review N-4): ingestion trace is never mixed in here, so a re-parse can always refresh it to the
     *  current source without a stale ER note permanently freezing the accepted M1 basis. */
    @Column(name = "basis_raw", length = 300)
    private String basisRaw;

    /**
     * Append-only ingestion trace (review N-4): channel-1 overlay notes (｜ER:doc / ｜ER消解 / ｜ER候选 /
     * ｜ER冲突目标) and quarantine notes (｜…待复核). Kept OUT of {@code basis_raw} (a dedicated, untruncated
     * longtext) so the doc-derived basis stays refreshable and the human-readable audit trail survives
     * independently. Not exposed to the workbench enumeration — that is {@link #candidateTargets}' job.
     */
    @Lob
    @Column(name = "ingest_trace", columnDefinition = "longtext")
    private String ingestTrace;

    /** source document, e.g. 03-逻辑数据模型/D01-销售订单域.md */
    @Column(name = "source_doc", length = 200)
    private String sourceDoc;

    /**
     * R3 structured multi-candidate targets (PLAN §3.2) as a JSON entry list — see
     * {@link Candidates}. Polymorphic A/B expansions and channel-1 deferred candidates
     * (多父候选/自证否认/目标冲突) land here instead of the 300-char {@code basis_raw}
     * so they can never be silently truncated and are enumerable by the workbench.
     */
    @Lob
    @Column(name = "candidate_targets", columnDefinition = "longtext")
    private String candidateTargets;

    /**
     * R3 discriminator column: the column whose value selects between candidates
     * (e.g. “按 order_type”). ONLY filled when a source document states it explicitly —
     * never inferred (R4). Null means the discriminator is still 待确认 from the docs.
     */
    @Column(name = "discriminator", length = 200)
    private String discriminator;

    /** who accepted/rejected this edge (M5 confirmation loop); null until a human verdict exists */
    @Column(name = "confirmed_by", length = 64)
    private String confirmedBy;

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
    public boolean isConflictFlag() { return conflictFlag; }
    public void setConflictFlag(boolean conflictFlag) { this.conflictFlag = conflictFlag; }
    public String getBasisRaw() { return basisRaw; }
    public void setBasisRaw(String basisRaw) { this.basisRaw = basisRaw; }
    public String getIngestTrace() { return ingestTrace; }
    public void setIngestTrace(String ingestTrace) { this.ingestTrace = ingestTrace; }
    public String getSourceDoc() { return sourceDoc; }
    public void setSourceDoc(String sourceDoc) { this.sourceDoc = sourceDoc; }
    public String getCandidateTargets() { return candidateTargets; }
    public void setCandidateTargets(String candidateTargets) { this.candidateTargets = candidateTargets; }
    public String getDiscriminator() { return discriminator; }
    public void setDiscriminator(String discriminator) { this.discriminator = discriminator; }
    public String getConfirmedBy() { return confirmedBy; }
    public void setConfirmedBy(String confirmedBy) { this.confirmedBy = confirmedBy; }
}
