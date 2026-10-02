package com.dam.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

/**
 * Code value / enum entry (dam_meta.dict_code_value, PLAN §M2.2):
 * category + value + meaning, e.g. the status enums flagged as unclear in
 * the model review list (05 §F-1).
 */
@Entity
@Table(name = "dict_code_value", indexes = @Index(name = "idx_code_cat", columnList = "category"))
public class DictCodeValue {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** enum family, e.g. confirm_status / certification_status */
    @Column(nullable = false, length = 64)
    private String category;

    @Column(name = "code_value", nullable = false, length = 64)
    private String codeValue;

    @Column(length = 200)
    private String meaning;

    @Column(nullable = false)
    private Integer ordinal = 0;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public String getCodeValue() { return codeValue; }
    public void setCodeValue(String codeValue) { this.codeValue = codeValue; }
    public String getMeaning() { return meaning; }
    public void setMeaning(String meaning) { this.meaning = meaning; }
    public Integer getOrdinal() { return ordinal; }
    public void setOrdinal(Integer ordinal) { this.ordinal = ordinal; }
}
