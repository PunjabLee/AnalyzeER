package com.dam.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Standard field library (dam_meta.dict_standard_field, PLAN §M2.1):
 * canonical name/type/length for shared columns — countermeasure for the
 * naming drift &amp; typo issues in the model review list (05 §C类).
 *
 * <p>NOTE (review #8): dict_* tables are governance TOOLING, distinct from the
 * governed business dictionary tables (D14 jf_* / OT sys_dict_*), which are plain
 * meta_asset rows.
 */
@Entity
@Table(name = "dict_standard_field")
public class DictStandardField {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "field_name", nullable = false, length = 64)
    private String fieldName;

    @Column(name = "data_type", length = 32)
    private String dataType;

    @Column(name = "length_val")
    private Integer lengthVal;

    @Column(length = 200)
    private String semantic;

    @Column(nullable = false)
    private Boolean enabled = true;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getFieldName() { return fieldName; }
    public void setFieldName(String fieldName) { this.fieldName = fieldName; }
    public String getDataType() { return dataType; }
    public void setDataType(String dataType) { this.dataType = dataType; }
    public Integer getLengthVal() { return lengthVal; }
    public void setLengthVal(Integer lengthVal) { this.lengthVal = lengthVal; }
    public String getSemantic() { return semantic; }
    public void setSemantic(String semantic) { this.semantic = semantic; }
    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }
}
