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
 * Cross-level mapping (dam_meta.model_mapping, PLAN §3.2-(4) / capability M4.4):
 * a bom_id ↔ ldm_id ↔ pdm_id triple recording that a business object (BOM) is realised by a
 * logical entity (LDM) which is backed by a physical table (PDM). {@code basis} states why the
 * mapping holds (here: name-verification against the physical catalog from the 05 §二 三分类),
 * {@code note} carries any caveat. {@code pdmId} may be null if a logical entity has no resolvable
 * physical table yet (marked 待确认 rather than fabricated).
 */
@Entity
@Table(name = "model_mapping",
        uniqueConstraints = @UniqueConstraint(columnNames = {"bom_id", "ldm_id"}),
        indexes = @Index(name = "idx_mapping_bom", columnList = "bom_id"))
public class ModelMapping {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "bom_id", nullable = false)
    private Long bomId;

    @Column(name = "ldm_id", nullable = false)
    private Long ldmId;

    @Column(name = "pdm_id")
    private Long pdmId;

    /** mapping basis / evidence (映射依据) */
    @Column(length = 200)
    private String basis;

    @Column(length = 500)
    private String note;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getBomId() { return bomId; }
    public void setBomId(Long bomId) { this.bomId = bomId; }
    public Long getLdmId() { return ldmId; }
    public void setLdmId(Long ldmId) { this.ldmId = ldmId; }
    public Long getPdmId() { return pdmId; }
    public void setPdmId(Long pdmId) { this.pdmId = pdmId; }
    public String getBasis() { return basis; }
    public void setBasis(String basis) { this.basis = basis; }
    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
}
