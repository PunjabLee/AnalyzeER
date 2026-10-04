package com.dam.lineage;

import com.dam.domain.MetaAsset;
import com.dam.domain.MetaRelation;
import com.dam.repository.MetaAssetRepository;
import com.dam.repository.MetaRelationRepository;
import com.dam.web.dto.Dtos.LineageEdge;
import com.dam.web.dto.Dtos.LineageNode;
import com.dam.web.dto.Dtos.LineageView;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * M3-2 table-level lineage over {@code meta_relation} via a MySQL-style <b>recursive CTE queried live</b>
 * (locked decision ③: no materialised closure table — the subgraph is computed on demand, so a relation
 * edit takes effect immediately; a closure table stays a reserved optimisation once scale demands it).
 *
 * <p><b>Edge direction</b> (inherited from the ingest model): every edge is {@code child(from) → parent(to)}
 * where {@code child} owns the FK column. Hence, from a root:
 * <ul>
 *   <li>{@link Direction#DOWNSTREAM} walks {@code to → from}: tables that REFERENCE the current node —
 *     i.e. "what is impacted if this table changes" (影响分析, PLAN G3);</li>
 *   <li>{@link Direction#UPSTREAM} walks {@code from → to}: tables the current node REFERENCES —
 *     i.e. "where this table's data comes from".</li>
 * </ul>
 *
 * <p><b>Cycle safety</b>: the corpus legitimately contains 2-cycles (two tables referencing each other on
 * different FK columns, e.g. {@code jf_customer ↔ jf_contract_quota_customer}). The CTE carries a
 * comma-delimited {@code path} and prunes any step whose next node already appears in it, plus a hard
 * depth clamp — so traversal always terminates and never re-expands a visited branch.
 *
 * <p><b>Dialect</b>: the same SQL runs on H2 (test, {@code MODE=MySQL}) and MySQL 8 (prod). Two things make
 * it portable and were validated by probe: the CTE declares its column list explicitly
 * ({@code lin (node, depth, ...)}) — required by H2, tolerated by MySQL — and the anchor widths the path
 * with {@code CAST(:root AS CHAR(1000))}.
 */
@Service
public class LineageService {

    /** traversal direction relative to the root */
    public enum Direction {
        /** who depends on me (impact analysis): join on the parent end, step to the child */
        DOWNSTREAM("r.to_asset_id", "r.from_asset_id"),
        /** what I depend on (source lineage): join on the child end, step to the parent */
        UPSTREAM("r.from_asset_id", "r.to_asset_id");

        final String joinCol;   // matched against the already-visited node
        final String nextCol;   // the newly reached node (also the FK-nullable guard)

        Direction(String joinCol, String nextCol) {
            this.joinCol = joinCol;
            this.nextCol = nextCol;
        }
    }

    private static final int HARD_MAX_DEPTH = 10;
    /**
     * Per-view node ceiling — PLAN 血缘可视化的单视图渲染上限 200 节点（NFR），超出即确定性截断；
     * 可经 {@code dam.lineage.node-limit} 配置下调以便测试截断分支（M-2）。
     */
    @org.springframework.beans.factory.annotation.Value("${dam.lineage.node-limit:200}")
    private int nodeLimit;

    private final EntityManager em;
    private final MetaAssetRepository assetRepo;
    private final MetaRelationRepository relRepo;

    public LineageService(EntityManager em, MetaAssetRepository assetRepo, MetaRelationRepository relRepo) {
        this.em = em;
        this.assetRepo = assetRepo;
        this.relRepo = relRepo;
    }

    /**
     * Trace a rooted lineage subgraph. {@code maxDepth} is clamped to {@code [1, }{@link #HARD_MAX_DEPTH}{@code ]}.
     * All node/edge endpoints resolve against the catalog — no synthetic nodes are ever introduced (R4/D8).
     */
    public LineageView trace(String rootName, long rootId, Direction dir, int maxDepth) {
        int depth = Math.max(1, Math.min(maxDepth, HARD_MAX_DEPTH));
        String next = dir.nextCol;
        String join = dir.joinCol;

        String sql =
                "WITH RECURSIVE lin (node, depth, parent_node, edge_id, path) AS (" +
                "  SELECT :root, 0, :root, :root, CAST(:root AS CHAR(1000))" +
                "  UNION ALL" +
                "  SELECT " + next + ", l.depth + 1, l.node, r.id, CONCAT(l.path, ',' , " + next + ")" +
                "  FROM meta_relation r JOIN lin l ON " + join + " = l.node" +
                "  WHERE " + next + " IS NOT NULL AND l.depth < :maxDepth" +
                "    AND LOCATE(CONCAT(',', " + next + ", ','), CONCAT(',', l.path, ',')) = 0" +
                ") SELECT node, depth, parent_node, edge_id FROM lin ORDER BY depth, node";

        // deterministic order (depth asc, node asc) BEFORE the row cap: parents always arrive before
        // their children, so a truncated run drops only the deepest tail and the kept set stays
        // parent-closed — the same LIMIT on unordered rows would not (M-2).
        List<?> rows = em.createNativeQuery(sql)
                .setParameter("root", rootId)
                .setParameter("maxDepth", depth)
                .setMaxResults(nodeLimit + 1)
                .getResultList();

        boolean truncated = rows.size() > nodeLimit;
        Map<Long, Integer> minDepth = new LinkedHashMap<>();
        Map<Long, Long> parentOf = new HashMap<>();    // shortest-path tree parent (impact-list paths)
        Map<Long, Long> viaEdgeOf = new HashMap<>();   // edge id that first reached the node
        Set<Long> edgeIds = new LinkedHashSet<>();
        for (Object row : rows) {
            Object[] r = (Object[]) row;
            long node = ((Number) r[0]).longValue();
            int d = ((Number) r[1]).intValue();
            // the depth-0 anchor row carries :root as a sentinel in parent_node/edge_id; H2 widens
            // those CTE columns to the CHAR type the sentinel parameter takes, so traversal values can
            // arrive as either Number (MySQL) or String (H2) — parse leniently, never blind-cast.
            Long parent = null;
            Long edge = null;
            if (d > 0) {
                parent = toLong(r[2]);
                edge = toLong(r[3]);
                if (edge != null) {
                    edgeIds.add(edge);
                }
            }
            Integer best = minDepth.get(node);
            if (best == null || d < best) {            // shortest path wins across multiple routes
                minDepth.put(node, d);
                if (d > 0) {                           // the depth-0 anchor carries :root as its own parent
                    parentOf.put(node, parent);
                    viaEdgeOf.put(node, edge);
                }
            }
        }
        // hard node cap: rows arrived depth-ordered, so evicting the tail keeps a parent-closed prefix
        if (minDepth.size() > nodeLimit) {
            List<Long> evicted = new ArrayList<>(minDepth.keySet()).subList(nodeLimit, minDepth.size());
            evicted.forEach(node -> {
                minDepth.remove(node);
                parentOf.remove(node);
                viaEdgeOf.remove(node);
            });
        }

        Map<Long, MetaAsset> byId = new HashMap<>();
        assetRepo.findAllById(minDepth.keySet()).forEach(a -> byId.put(a.getId(), a));

        List<LineageNode> nodes = new ArrayList<>();
        for (Map.Entry<Long, Integer> e : minDepth.entrySet()) {
            MetaAsset a = byId.get(e.getKey());
            if (a == null) {
                continue;   // edge pointed at an id no longer in the catalog — skip, never fabricate
            }
            boolean isRoot = e.getKey() == rootId;
            nodes.add(new LineageNode(a.getId(), a.getName(), e.getValue(), a.getGrading(), a.getDomainCode(),
                    isRoot ? null : parentOf.get(e.getKey()), isRoot ? null : viaEdgeOf.get(e.getKey())));
        }
        nodes.sort(Comparator.comparingInt(LineageNode::depth)
                .thenComparing(LineageNode::name, Comparator.nullsLast(Comparator.naturalOrder())));

        List<LineageEdge> edges = new ArrayList<>();
        Set<Long> finalIds = new HashSet<>();
        nodes.forEach(n -> finalIds.add(n.assetId()));
        for (MetaRelation r : relRepo.findAllById(edgeIds)) {
            // closed-subgraph invariant survives truncation AND catalog evaporation: keep an edge
            // only when BOTH endpoints are in the returned node set (never a dangling half-edge).
            if (!finalIds.contains(r.getFromAssetId()) || !finalIds.contains(r.getToAssetId())) {
                continue;
            }
            edges.add(new LineageEdge(r.getId(), r.getFromAssetId(), nameOf(byId, r.getFromAssetId()),
                    r.getFromColumn(), r.getToAssetId(), nameOf(byId, r.getToAssetId()),
                    r.getCardinality(), r.getEvidenceLevel(), r.getConfidence(), r.getOrigin()));
        }

        return new LineageView(rootName, rootId, dir.name(), depth, nodes.size(), truncated, nodes, edges);
    }

    private static String nameOf(Map<Long, MetaAsset> byId, Long id) {
        MetaAsset a = id == null ? null : byId.get(id);
        return a == null ? null : a.getName();
    }

    /** dialect-tolerant BIGINT reader: MySQL returns Number, H2 may stringify CTE-unified columns */
    private static Long toLong(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.parseLong(v.toString().trim());
        } catch (NumberFormatException notNumeric) {
            return null;   // sentinel text (e.g. the anchor's own :root cast) — treat as absent
        }
    }
}
