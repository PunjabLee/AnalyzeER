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
    /** wall-clock budget for the recursive CTE (review N-10: dense subgraphs can explode paths). */
    private static final int STATEMENT_TIMEOUT_MS = 15_000;
    /** rows-per-node slack for the runaway budget: multi-route convergence is path-pruned to
     *  a small constant in this corpus (measured 73 rows / 41 nodes ≈ 1.8), 4× is generous. */
    private static final int ROWS_PER_NODE_BUDGET = 4;
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
                ") SELECT node, depth, parent_node, edge_id FROM lin ORDER BY depth, node, edge_id";

        // Determinism without window functions (review N-6/N-7) — H2 cannot combine a recursive CTE
        // with a ROW_NUMBER derived table (probe 2026-10-04: anchor row nullifies, recursion is lost),
        // so TOTAL ORDER in SQL (depth, node, edge_id) + "first row per node wins" in Java gives the
        // same canonical tree: every node's row is its (min depth, min edge) arrival, parent/edge
        // pairing comes from that one row. Depth-ordered rows mean parents always precede children:
        // once the NODE cap is hit only new (deeper) nodes are refused, so the kept set stays
        // parent-closed (the cap counts NODES, not rows — the row budget is runaway protection only).
        int rowBudget = (nodeLimit + 1) * ROWS_PER_NODE_BUDGET;
        List<?> rows = em.createNativeQuery(sql)
                .setHint("jakarta.persistence.query.timeout", STATEMENT_TIMEOUT_MS)
                .setParameter("root", rootId)
                .setParameter("maxDepth", depth)
                .setMaxResults(rowBudget + 1)
                .getResultList();

        boolean budgetHit = rows.size() > rowBudget;
        Map<Long, Integer> minDepth = new LinkedHashMap<>();
        Map<Long, Long> parentOf = new HashMap<>();    // shortest-path tree parent (impact-list paths)
        Map<Long, Long> viaEdgeOf = new HashMap<>();   // canonical edge that reached the node
        boolean capHit = false;
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
            }
            if (minDepth.containsKey(node)) {
                continue;                              // total order: first row IS the canonical row
            }
            if (minDepth.size() >= nodeLimit) {
                capHit = true;                         // node ceiling reached: refuse NEW nodes only
                continue;
            }
            minDepth.put(node, d);
            if (d > 0) {                               // the depth-0 anchor carries :root as its own parent
                parentOf.put(node, parent);
                viaEdgeOf.put(node, edge);
            }
        }
        boolean truncated = capHit || budgetHit;

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
        // Edge semantics (review N-6 follow-up): the INDUCED edge set — every stored relation whose
        // BOTH endpoints sit in the returned node set. Deterministic (a "traversed-edges" set depends
        // on arrival order and silently drops path-pruning back edges, e.g. 48 vs 49 on the hub),
        // and the closed-subgraph invariant holds by construction, surviving truncation AND
        // catalog evaporation — a half-edge is never emitted.
        List<MetaRelation> induced = new ArrayList<>();
        for (MetaRelation r : relRepo.findAll(org.springframework.data.domain.Sort.by("id"))) {
            if (finalIds.contains(r.getFromAssetId()) && finalIds.contains(r.getToAssetId())) {
                induced.add(r);
            }
        }
        for (MetaRelation r : induced) {
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
