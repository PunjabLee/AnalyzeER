package com.dam.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Registered metadata source / ingestion run (dam_meta.meta_source).
 * M0: one row per DDL file ingestion.
 */
@Entity
@Table(name = "meta_source")
public class MetaSource {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 200)
    private String name;

    /** DDL / MD / MANUAL */
    @Column(length = 32)
    private String type;

    @Column(name = "imported_at")
    private Instant importedAt;

    /** number of tables parsed (census: expected 1322 for test_erp.sql) */
    @Column(name = "table_count")
    private Integer tableCount;

    /** total physical columns parsed across all tables */
    @Column(name = "column_count")
    private Long columnCount;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public Instant getImportedAt() { return importedAt; }
    public void setImportedAt(Instant importedAt) { this.importedAt = importedAt; }
    public Integer getTableCount() { return tableCount; }
    public void setTableCount(Integer tableCount) { this.tableCount = tableCount; }
    public Long getColumnCount() { return columnCount; }
    public void setColumnCount(Long columnCount) { this.columnCount = columnCount; }
}
