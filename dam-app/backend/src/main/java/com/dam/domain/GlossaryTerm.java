package com.dam.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * Business glossary term (dam_meta.glossary_term, PLAN §3.2-(3) / capability M3.1).
 * A term carries a name, definition, aliases/synonyms, a business caliber (口径), the
 * owning domain, a responsible owner ({@code ->sys_user}) and a review status
 * (DRAFT -> REVIEW -> PUBLISHED, M3.4 change flow).
 *
 * <p>Boundary note (R4): authoritative term wording/含义 needs a business Owner; the
 * startup seed only installs clearly-labelled example terms in DRAFT so the binding
 * capability is demonstrable without asserting business facts.
 */
@Entity
@Table(name = "glossary_term", uniqueConstraints = @UniqueConstraint(columnNames = "name"))
public class GlossaryTerm {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 128)
    private String name;

    @Column(length = 2000)
    private String definition;

    /** aliases / synonyms, comma-separated (M3.1) */
    @Column(length = 500)
    private String aliases;

    /** business caliber (业务口径) */
    @Column(length = 2000)
    private String caliber;

    @Column(name = "domain_code", length = 16)
    private String domainCode;

    /** responsible owner -> sys_user.id (M3.1) */
    @Column(name = "owner_id")
    private Long ownerId;

    /** DRAFT / REVIEW / PUBLISHED (M3.4 change flow) */
    @Column(length = 16, nullable = false)
    private String status = "DRAFT";

    /** free note, e.g. why a seeded example is not yet authoritative (R4) */
    @Column(length = 500)
    private String note;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDefinition() { return definition; }
    public void setDefinition(String definition) { this.definition = definition; }
    public String getAliases() { return aliases; }
    public void setAliases(String aliases) { this.aliases = aliases; }
    public String getCaliber() { return caliber; }
    public void setCaliber(String caliber) { this.caliber = caliber; }
    public String getDomainCode() { return domainCode; }
    public void setDomainCode(String domainCode) { this.domainCode = domainCode; }
    public Long getOwnerId() { return ownerId; }
    public void setOwnerId(Long ownerId) { this.ownerId = ownerId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
}
