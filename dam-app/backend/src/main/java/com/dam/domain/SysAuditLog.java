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
 * Operation audit trail (dam_meta.sys_audit_log, PLAN M7.2): who changed what, when.
 */
@Entity
@Table(name = "sys_audit_log", indexes = @Index(name = "idx_audit_at", columnList = "at_ts"))
public class SysAuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 64)
    private String username;

    /** LOGIN / INGEST / GOVERNANCE / DICT / ... */
    @Column(nullable = false, length = 64)
    private String action;

    @Column(name = "target", length = 200)
    private String target;

    @Column(name = "detail", length = 1000)
    private String detail;

    @Column(name = "at_ts", nullable = false)
    private Instant atTs;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }
    public String getTarget() { return target; }
    public void setTarget(String target) { this.target = target; }
    public String getDetail() { return detail; }
    public void setDetail(String detail) { this.detail = detail; }
    public Instant getAtTs() { return atTs; }
    public void setAtTs(Instant atTs) { this.atTs = atTs; }
}
