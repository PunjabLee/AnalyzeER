package com.dam.lineage;

import com.dam.domain.MetaAsset;
import com.dam.domain.MetaRelation;
import com.dam.lineage.LineageService.Direction;
import com.dam.repository.MetaAssetRepository;
import com.dam.repository.MetaRelationRepository;
import com.dam.web.dto.Dtos.LineageNode;
import com.dam.web.dto.Dtos.LineageView;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * M3 exit-criteria item "变更影响清单可导出" (PLAN G3): turns the DOWNSTREAM lineage trace of a
 * rooted table into a flat, auditable impact list — one row per impacted table (the root itself
 * excluded) carrying the shortest impact path root→…→table and the FK relation through which the
 * impact propagates. Exportable as CSV (Excel-ready, BOM'd) / JSON / YAML.
 *
 * <p>Rows derive exclusively from the live lineage subgraph (recursive CTE, decision ③): every
 * affected asset and every via-relation is a real stored record, nothing is synthesized (R4/D8).
 */
@Service
public class ImpactExportService {

    /** one impacted table: how deep, through which parent+FK column, and the full path from the root */
    public record ImpactRow(
            String affectedAsset, String domainCode, String grading, int depth,
            String viaParent, String viaColumn, String impactPath,
            String cardinality, String evidenceLevel, Double confidence, String origin) { }

    /** the whole exportable list: header metadata + rows ordered by depth then name (view order) */
    public record ImpactReport(
            String rootAsset, Long rootAssetId, int maxDepth, boolean truncated,
            Instant generatedAt, int impactedCount, List<ImpactRow> rows) { }

    private final MetaAssetRepository assetRepo;
    private final MetaRelationRepository relRepo;
    private final LineageService lineage;
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final YAMLMapper yaml = new YAMLMapper();

    @jakarta.annotation.PostConstruct
    void registerYamlModules() {
        yaml.findAndRegisterModules();   // in-place module registration (Instant → ISO-8601)
    }

    public ImpactExportService(MetaAssetRepository assetRepo, MetaRelationRepository relRepo,
                               LineageService lineage) {
        this.assetRepo = assetRepo;
        this.relRepo = relRepo;
        this.lineage = lineage;
    }

    /** DOWNSTREAM trace of the named table flattened into the impact list (case-insensitive name). */
    public ImpactReport build(String rootName, int maxDepth) {
        MetaAsset root = assetRepo.findByNameIgnoreCase(rootName.trim()).orElseThrow();
        long rootId = root.getId();
        LineageView view = lineage.trace(root.getName(), root.getId(), Direction.DOWNSTREAM, maxDepth);

        Map<Long, LineageNode> byId = new HashMap<>();
        view.nodes().forEach(n -> byId.put(n.assetId(), n));
        Map<Long, MetaRelation> edgeById = new LinkedHashMap<>();
        view.edges().forEach(e -> edgeById.put(e.relationId(), null));
        relRepo.findAllById(edgeById.keySet()).forEach(r -> edgeById.put(r.getId(), r));

        List<ImpactRow> rows = view.nodes().stream()
                .filter(n -> n.assetId() != rootId)              // the list reports IMPACT, not the root
                .map(n -> toRow(n, root.getName(), byId, edgeById))
                .toList();
        return new ImpactReport(root.getName(), root.getId(), view.maxDepth(),
                view.truncated(), Instant.now(), rows.size(), rows);
    }

    private ImpactRow toRow(LineageNode n, String rootName,
                            Map<Long, LineageNode> byId, Map<Long, MetaRelation> edgeById) {
        MetaRelation via = n.viaRelationId() == null ? null : edgeById.get(n.viaRelationId());
        LineageNode parent = n.parentNode() == null ? null : byId.get(n.parentNode());
        return new ImpactRow(n.name(), n.domainCode(), n.grading(), n.depth(),
                parent == null ? null : parent.name(),
                via == null ? null : via.getFromColumn(),
                impactPath(n, byId, rootName),
                via == null ? null : via.getCardinality(),
                via == null ? null : via.getEvidenceLevel(),
                via == null ? null : via.getConfidence(),
                via == null ? null : via.getOrigin());
    }

    /** walk the shortest-path tree upward: root → … → node; bounded so a broken parent can never hang. */
    private static String impactPath(LineageNode n, Map<Long, LineageNode> byId, String rootName) {
        java.util.Deque<String> names = new java.util.ArrayDeque<>();
        LineageNode cur = n;
        int guard = 0;
        while (cur != null && guard++ <= 64) {
            names.addFirst(cur.name());
            if (cur.parentNode() == null) {
                break;                       // reached the root end of the tree
            }
            cur = byId.get(cur.parentNode());
        }
        String top = names.peekFirst();
        if (top != null && !top.equals(rootName)) {
            names.addFirst(rootName + "…");  // defensive: chain didn't close on the root — flag it
        }
        return String.join(" → ", names);
    }

    public String toJson(ImpactReport report) {
        try {
            return json.writerWithDefaultPrettyPrinter().writeValueAsString(report);
        } catch (Exception e) {
            throw new IllegalStateException("impact json export failed", e);
        }
    }

    public String toYaml(ImpactReport report) {
        try {
            return yaml.writeValueAsString(report);
        } catch (Exception e) {
            throw new IllegalStateException("impact yaml export failed", e);
        }
    }

    /** Excel-ready CSV: UTF-8 BOM + header; every field RFC4180-escaped. */
    public String toCsv(ImpactReport report) {
        StringBuilder sb = new StringBuilder("\uFEFF");
        sb.append("affected_asset,domain_code,grading,depth,via_parent,via_column,")
                .append("impact_path,cardinality,evidence_level,confidence,origin\n");
        for (ImpactRow r : report.rows()) {
            sb.append(csv(r.affectedAsset())).append(',')
              .append(csv(r.domainCode())).append(',')
              .append(csv(r.grading())).append(',')
              .append(r.depth()).append(',')
              .append(csv(r.viaParent())).append(',')
              .append(csv(r.viaColumn())).append(',')
              .append(csv(r.impactPath())).append(',')
              .append(csv(r.cardinality())).append(',')
              .append(csv(r.evidenceLevel())).append(',')
              .append(r.confidence() == null ? "" : r.confidence()).append(',')
              .append(csv(r.origin())).append('\n');
        }
        return sb.toString();
    }

    private static String csv(String v) {
        if (v == null || v.isEmpty()) {
            return "";
        }
        if (v.indexOf(',') >= 0 || v.indexOf('"') >= 0 || v.indexOf('\n') >= 0) {
            return '"' + v.replace("\"", "\"\"") + '"';
        }
        return v;
    }
}
