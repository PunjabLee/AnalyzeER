package com.dam.web.dto;

import java.util.List;
import java.util.Map;

/**
 * Read DTOs for the three-level model view (capability M4, PLAN §3.2-(4)).
 * The BOM list is grouped by category; a trace expands one BOM into its logical entities and the
 * resolved physical tables; overview carries build stats for the UI header.
 */
public final class ModelDtos {

    private ModelDtos() { }

    /** a business object row in the left pane */
    public record BomSummary(
            Long id,
            String name,
            String label,
            String category,
            String domainCode,
            long childCount) { }

    /** one BOM→LDM→PDM chain link (pdm/asset null when the physical table could not be resolved) */
    public record TraceNode(
            Long ldmId,
            String ldmName,
            String domainCode,
            Long pdmId,
            Long assetId,
            String assetUrn,
            Integer columnCount,
            String basis,
            boolean resolved) { }

    /** full trace of one business object */
    public record BomTrace(
            Long id,
            String name,
            String label,
            String category,
            String domainCode,
            String description,
            String sourceExpr,
            List<TraceNode> nodes) { }

    /** build stats for the view header */
    public record Overview(
            int bomTotal,
            int ldmTotal,
            int pdmTotal,
            int mappingTotal,
            int bomWithoutResolution,
            Map<String, Integer> bomByCategory) { }
}
