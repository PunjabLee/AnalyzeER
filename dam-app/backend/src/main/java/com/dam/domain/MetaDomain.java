package com.dam.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * Business domain registry (dam_meta.meta_domain, PLAN §3.2).
 * Codes: D01..D18 (A-grade jf_ domains), OT (no-prefix legacy), B (isomorphic), C (backup/test).
 */
@Entity
@Table(name = "meta_domain", uniqueConstraints = @UniqueConstraint(columnNames = "code"))
public class MetaDomain {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 16)
    private String code;

    @Column(nullable = false, length = 100)
    private String name;

    /** grading bucket this domain belongs to: A / B / C */
    @Column(name = "grading", nullable = false, length = 4)
    private String grading;

    /** table count declared by er-model/00-总览 (hard-checked), null for B/C buckets */
    @Column(name = "declared_count")
    private Integer declaredCount;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getGrading() { return grading; }
    public void setGrading(String grading) { this.grading = grading; }
    public Integer getDeclaredCount() { return declaredCount; }
    public void setDeclaredCount(Integer declaredCount) { this.declaredCount = declaredCount; }
}
