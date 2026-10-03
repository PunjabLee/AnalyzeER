package com.dam.web.dto;

import java.util.List;

/**
 * Read/write DTOs for the business glossary (capability M3, PLAN §3.2-(3)).
 * Nested records mirror {@link Dtos} to keep the API surface small.
 */
public final class GlossaryDtos {

    private GlossaryDtos() { }

    /** create/update payload for a term (M3.1) */
    public record TermUpsert(
            String name,
            String definition,
            String aliases,
            String caliber,
            String domainCode,
            Long ownerId,
            String status,
            String note) { }

    /** bind request: attach a term to a table (assetId) and/or a column (M3.2 / M9.2 drag) */
    public record TermBinding(
            Long assetId,
            Long columnId,
            String refType) { }

    /** a resolved reference (asset/column names filled server-side for display) */
    public record RefView(
            Long id,
            Long assetId,
            String assetName,
            Long columnId,
            String columnName,
            String refType) { }

    /** full term view: term fields + owner display name + resolved references */
    public record TermView(
            Long id,
            String name,
            String definition,
            String aliases,
            String caliber,
            String domainCode,
            Long ownerId,
            String ownerName,
            String status,
            String note,
            List<RefView> refs) { }

    /** lightweight term summary for list panels */
    public record TermSummary(
            Long id,
            String name,
            String domainCode,
            String status,
            long refCount) { }
}
