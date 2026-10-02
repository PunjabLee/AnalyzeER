package com.dam.web.dto;

import java.util.List;

/**
 * Read DTOs for the asset catalog API (M0). Grouped as nested records to keep the surface small.
 */
public final class Dtos {

    private Dtos() { }

    public record AssetSummary(
            String urn,
            String name,
            String grading,
            String domainCode,
            String prefixFamily,
            Boolean hasPk,
            Integer columnCount,
            String tableComment,
            String certificationStatus,
            String sensitivityLevel,
            Boolean deprecated,
            Long ownerId,
            Long stewardId) { }

    public record ColumnView(
            Integer ordinal,
            String name,
            String type,
            String nullable,
            String defaultVal,
            String keyHint,
            String meaning,
            String sourceLayer) { }

    /** one inferred relation edge as shown on the asset detail page (M1.3) */
    public record RelationView(
            Long id,
            String direction,       // out（本表→） / in（→本表）
            String fromName,
            String fromColumn,
            String toName,          // null when unresolved
            String toColumn,
            String targetRaw,
            String evidenceLevel,
            String origin,
            Double confidence,
            String confirmStatus,
            String crossDomain,
            String sourceDoc) { }

    public record AssetDetail(
            AssetSummary summary,
            List<ColumnView> columns,
            List<RelationView> relations) { }

    /** governance attribute edit payload (M1.5) */
    public record GovernanceUpdate(
            String certificationStatus,
            String sensitivityLevel,
            Boolean deprecated,
            String deprecationNote,
            Long ownerId,
            Long stewardId) { }
}
