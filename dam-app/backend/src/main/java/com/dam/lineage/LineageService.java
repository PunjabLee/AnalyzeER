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
    /** per-view node ceiling (PLAN §2.3: a single view renders ≤ 200 nodes); beyond this → truncated */
    private static final int NODE_LIMIT = 300;

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
                ") SELECT node, depth, parent_node, edge_id FROM lin";

        List<?> rows = em.createNativeQuery(sql)
                .setParameter("root", rootId)
                .setParameter("maxDepth", depth)
                .setMaxResults(NODE_LIMIT + 1)
                .getResultList();

        boolean truncated = rows.size() > NODE_LIMIT;
        Map<Long, Integer> minDepth = new LinkedHashMap<>();
        Set<Long> edgeIds = new LinkedHashSet<>();
        for (Object row : rows) {
            Object[] r = (Object[]) row;
            long node = ((Number) r[0]).longValue();
            int d = ((Number) r[1]).intValue();
            minDepth.merge(node, d, Math::min);           // shortest path wins across multiple routes
            // the depth-0 anchor row carries :root as a sentinel in parent_node/edge_id (kept BIGINT so
            // both H2 and MySQL resolve the column type consistently — MySQL rejects CAST(NULL AS BIGINT));
            // only real traversal rows (depth > 0) contribute an edge id.
            if (d > 0 && r[3] != null) {
                edgeIds.add(((Number) r[3]).longValue());
            }
        }

        Map<Long, MetaAsset> byId = new HashMap<>();
        assetRepo.findAllById(minDepth.keySet()).forEach(a -> byId.put(a.getId(), a));

        List<LineageNode> nodes = new ArrayList<>();
        for (Map.Entry<Long, Integer> e : minDepth.entrySet()) {
            MetaAsset a = byId.get(e.getKey());
            if (a == null) {
                continue;   // edge pointed at an id no longer in the catalog — skip, never fabricate
            }
            nodes.add(new LineageNode(a.getId(), a.getName(), e.getValue(), a.getGrading(), a.getDomainCode()));
        }
        nodes.sort(Comparator.comparingInt(LineageNode::depth)
                .thenComparing(LineageNode::name, Comparator.nullsLast(Comparator.naturalOrder())));

        List<LineageEdge> edges = new ArrayList<>();
        for (MetaRelation r : relRepo.findAllById(edgeIds)) {
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
}
