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
            String tableComment) { }

    public record ColumnView(
            Integer ordinal,
            String name,
            String type,
            String nullable,
            String defaultVal,
            String keyHint,
            String meaning,
            String sourceLayer) { }

    public record AssetDetail(
            AssetSummary summary,
            List<ColumnView> columns) { }
}
