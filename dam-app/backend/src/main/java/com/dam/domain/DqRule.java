package com.dam.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Data-quality rule (dam_meta.dq_rule, PLAN §6.1). category=structure rules run
 * in M1; relationship rules stay disabled until lineage lands (review #6).
 * checker selects the built-in scan implementation; param carries its argument.
 */
@Entity
@Table(name = "dq_rule")
public class DqRule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 32)
    private String code;

    @Column(nullable = false, length = 128)
    private String name;

    /** structure | relationship */
    @Column(nullable = false, length = 16)
    private String category;

    /** NO_PK | CHARSET | COLUMN_NAMING | TABLE_NAMING (built-in scanners) */
    @Column(name = "checker", nullable = false, length = 32)
    private String checker;

    /** checker argument: regex pattern or expected charset */
    @Column(length = 200)
    private String param;

    /** optional scan slice, e.g. grading=A to skip framework-table noise */
    @Column(name = "scope_grading", length = 8)
    private String scopeGrading;

    /** high | medium | low */
    @Column(nullable = false, length = 16)
    private String severity = "medium";

    @Column(nullable = false)
    private Boolean enabled = true;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public String getChecker() { return checker; }
    public void setChecker(String checker) { this.checker = checker; }
    public String getParam() { return param; }
    public void setParam(String param) { this.param = param; }
    public String getScopeGrading() { return scopeGrading; }
    public void setScopeGrading(String scopeGrading) { this.scopeGrading = scopeGrading; }
    public String getSeverity() { return severity; }
    public void setSeverity(String severity) { this.severity = severity; }
    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }
}
