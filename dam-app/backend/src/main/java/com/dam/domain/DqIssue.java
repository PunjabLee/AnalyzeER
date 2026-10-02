package com.dam.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Issue hit by a dq scan (dam_meta.dq_issue, PLAN §6.1/6.2): one row per
 * rule × violating object; becomes the input of later remediation tickets (dq_ticket).
 */
@Entity
@Table(name = "dq_issue", indexes = {
        @Index(name = "idx_issue_rule", columnList = "rule_code"),
        @Index(name = "idx_issue_status", columnList = "status")})
public class DqIssue {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "rule_code", nullable = false, length = 32)
    private String ruleCode;

    @Column(name = "asset_urn", nullable = false, length = 300)
    private String assetUrn;

    /** set for column-level violations */
    @Column(name = "column_name", length = 128)
    private String columnName;

    @Column(length = 500)
    private String detail;

    @Column(name = "scanned_at", nullable = false)
    private Instant scannedAt;

    /** open | resolved | ignored */
    @Column(nullable = false, length = 16)
    private String status = "open";

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getRuleCode() { return ruleCode; }
    public void setRuleCode(String ruleCode) { this.ruleCode = ruleCode; }
    public String getAssetUrn() { return assetUrn; }
    public void setAssetUrn(String assetUrn) { this.assetUrn = assetUrn; }
    public String getColumnName() { return columnName; }
    public void setColumnName(String columnName) { this.columnName = columnName; }
    public String getDetail() { return detail; }
    public void setDetail(String detail) { this.detail = detail; }
    public Instant getScannedAt() { return scannedAt; }
    public void setScannedAt(Instant scannedAt) { this.scannedAt = scannedAt; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
}
