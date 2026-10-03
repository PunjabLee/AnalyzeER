package com.dam.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * Platform user (dam_meta.sys_user, PLAN §3.2-(5)) — the Owner/Steward first-level entity (评审#4).
 * M1 keeps it minimal: seeded from application.yml users and used by governance assignment + audit.
 */
@Entity
@Table(name = "sys_user", uniqueConstraints = @UniqueConstraint(columnNames = "username"))
public class SysUser {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 64)
    private String username;

    @Column(name = "display_name", length = 100)
    private String displayName;

    /** ADMIN / STEWARD / READONLY (M1 basic RBAC roles, PLAN M7.1) */
    @Column(length = 32)
    private String role;

    /**
     * BCrypt hash of the account password (D5 decision 2026-10-03: sys_user is the
     * authoritative identity source for the POC, replacing the in-memory users).
     * Nullable only so an existing MySQL row survives the schema add; UserSeed always
     * backfills it, and the DB-backed UserDetailsService rejects a null hash at login.
     */
    @Column(name = "password_hash", length = 100)
    private String passwordHash;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }
    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }
}
