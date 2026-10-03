package com.dam.web.dto;

import java.util.List;

/**
 * Read DTOs for the asset catalog API (M0). Grouped as nested records to keep the surface small.
 */
public final class Dtos {

    private Dtos() { }

    public record AssetSummary(
            Long id,
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
            Long id,
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
            String cardinality,     // 1:1 / 1:N / N:M (channel-1 ER overlay; null if not overlaid)
            boolean conflictFlag,   // channel-2 vs ER target conflict / multi-parent (S3-1, filterable)
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

    /** catalog facets bucketed so domain codes and grading codes never collide (H-3) */
    public record Facets(java.util.Map<String, Long> domains,
                         java.util.Map<String, Long> gradings,
                         long total) { }

    /**
     * Column reorder payload (M9.1 field-drag-sort): the full ordered list of column ids
     * for one asset. Ordinals are re-assigned 0..n-1 by position. Must cover exactly the
     * asset's current columns (no missing / foreign ids).
     */
    public record ColumnOrder(java.util.List<Long> columnIds) { }

    /**
     * M3-2 lineage: one reachable table in a recursive-blood lineage trace.
     * depth = minimum hops from the root (a node reachable by several paths keeps its shortest).
     */
    public record LineageNode(
            Long assetId,
            String name,
            int depth,
            String grading,
            String domainCode) { }

    /** one traversed edge of the lineage subgraph (endpoints resolved against the catalog) */
    public record LineageEdge(
            Long relationId,
            Long fromAssetId,
            String fromName,       // child (FK owner)
            String fromColumn,
            Long toAssetId,
            String toName,         // parent (referenced)
            String cardinality,
            String evidenceLevel,
            Double confidence,
            String origin) { }

    /**
     * Rooted lineage subgraph (PLAN G3 正/反向追溯). direction=DOWNSTREAM (who depends on me /
     * impact analysis) or UPSTREAM (what I depend on). truncated when the subgraph exceeds the
     * per-view node cap (PLAN §2.3 ≤ 200 nodes per single view).
     */
    public record LineageView(
            String rootName,
            Long rootAssetId,
            String direction,
            int maxDepth,
            int nodeCount,
            boolean truncated,
            java.util.List<LineageNode> nodes,
            java.util.List<LineageEdge> edges) { }
}
