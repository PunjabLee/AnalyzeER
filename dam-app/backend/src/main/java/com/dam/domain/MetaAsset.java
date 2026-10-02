package com.dam.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * Table/view level asset (dam_meta.meta_asset). M0 subset of PLAN §3.2.
 * asset_urn is the stable cross-version identity: mysql:{schema}:{table}.
 */
@Entity
@Table(name = "meta_asset",
        uniqueConstraints = @UniqueConstraint(columnNames = "asset_urn"),
        indexes = @Index(name = "idx_asset_domain", columnList = "domain_code"))
public class MetaAsset {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "asset_urn", nullable = false, length = 300)
    private String assetUrn;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(name = "schema_name", length = 100)
    private String schemaName;

    /** A / B / C grading (assigned in M1; null at M0) */
    @Column(length = 4)
    private String grading;

    /** D01..D18 / OT / B / C domain code (assigned in M1; null at M0) */
    @Column(name = "domain_code", length = 16)
    private String domainCode;

    /** jf_ / lcap_ / N(hex) / P(hex) / (no-prefix) family */
    @Column(name = "prefix_family", length = 32)
    private String prefixFamily;

    @Column(name = "has_pk")
    private Boolean hasPk;

    @Column(name = "table_comment", length = 500)
    private String tableComment;

    @Column(length = 64)
    private String charset;

    @Column(length = 64)
    private String collate;

    @Column(name = "column_count")
    private Integer columnCount;

    @Column(name = "source_id")
    private Long sourceId;

    /** 治理属性（M1.5，评审#4）：认证 / 弃用 / 敏感分级 / 负责人 */
    @Column(name = "certification_status", length = 16)
    private String certificationStatus = "未认证"; // 认证 / 待审 / 未认证

    @Column(name = "sensitivity_level", length = 16)
    private String sensitivityLevel; // PII / 机密 / 内部 / 公开

    @Column(name = "deprecated")
    private Boolean deprecated = false;

    @Column(name = "deprecation_note", length = 500)
    private String deprecationNote;

    @Column(name = "owner_id")
    private Long ownerId;

    @Column(name = "steward_id")
    private Long stewardId;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getAssetUrn() { return assetUrn; }
    public void setAssetUrn(String assetUrn) { this.assetUrn = assetUrn; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getSchemaName() { return schemaName; }
    public void setSchemaName(String schemaName) { this.schemaName = schemaName; }
    public String getGrading() { return grading; }
    public void setGrading(String grading) { this.grading = grading; }
    public String getDomainCode() { return domainCode; }
    public void setDomainCode(String domainCode) { this.domainCode = domainCode; }
    public String getPrefixFamily() { return prefixFamily; }
    public void setPrefixFamily(String prefixFamily) { this.prefixFamily = prefixFamily; }
    public Boolean getHasPk() { return hasPk; }
    public void setHasPk(Boolean hasPk) { this.hasPk = hasPk; }
    public String getTableComment() { return tableComment; }
    public void setTableComment(String tableComment) { this.tableComment = tableComment; }
    public String getCharset() { return charset; }
    public void setCharset(String charset) { this.charset = charset; }
    public String getCollate() { return collate; }
    public void setCollate(String collate) { this.collate = collate; }
    public Integer getColumnCount() { return columnCount; }
    public void setColumnCount(Integer columnCount) { this.columnCount = columnCount; }
    public Long getSourceId() { return sourceId; }
    public void setSourceId(Long sourceId) { this.sourceId = sourceId; }
    public String getCertificationStatus() { return certificationStatus; }
    public void setCertificationStatus(String certificationStatus) { this.certificationStatus = certificationStatus; }
    public String getSensitivityLevel() { return sensitivityLevel; }
    public void setSensitivityLevel(String sensitivityLevel) { this.sensitivityLevel = sensitivityLevel; }
    public Boolean getDeprecated() { return deprecated; }
    public void setDeprecated(Boolean deprecated) { this.deprecated = deprecated; }
    public String getDeprecationNote() { return deprecationNote; }
    public void setDeprecationNote(String deprecationNote) { this.deprecationNote = deprecationNote; }
    public Long getOwnerId() { return ownerId; }
    public void setOwnerId(Long ownerId) { this.ownerId = ownerId; }
    public Long getStewardId() { return stewardId; }
    public void setStewardId(Long stewardId) { this.stewardId = stewardId; }
}
