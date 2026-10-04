package com.dam;

import com.dam.domain.MetaAsset;
import com.dam.lineage.LineageService;
import com.dam.lineage.LineageService.Direction;
import com.dam.repository.MetaAssetRepository;
import com.dam.web.dto.Dtos.LineageEdge;
import com.dam.web.dto.Dtos.LineageNode;
import com.dam.web.dto.Dtos.LineageView;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M3-2 lineage query integrity (runs on H2 MODE=MySQL with the full ingested catalog — the same
 * recursive CTE the production MySQL path uses). Guards PLAN G3: the {@code jf_sales_order} hub must be
 * traceable both directions, and the trace must never fabricate a node or leave a dangling edge.
 */
@SpringBootTest
class LineageQueryTest {

    @Autowired
    LineageService lineage;
    @Autowired
    MetaAssetRepository assetRepo;

    private MetaAsset hub() {
        return assetRepo.findByNameIgnoreCase("jf_sales_order").orElseThrow(
                () -> new AssertionError("jf_sales_order must exist in the ingested catalog"));
    }

    @Test
    void downstreamImpactFromSalesOrderHubIsReachable() {
        MetaAsset r = hub();
        LineageView v = lineage.trace(r.getName(), r.getId(), Direction.DOWNSTREAM, 10);
        assertThat(v.direction()).isEqualTo("DOWNSTREAM");
        assertThat(v.nodes()).anySatisfy(n -> {
            assertThat(n.assetId()).isEqualTo(r.getId());
            assertThat(n.depth()).isZero();   // the root is always the depth-0 anchor
        });
        // a genuinely-connected hub reaches a meaningful downstream impact set — EXACT regression
        // numbers (M-3, re-anchored to the INDUCED edge-set semantics of review N-6): H2 and real
        // MySQL 8 measured bit-identical; edges = every relation with BOTH endpoints in the set.
        assertThat(v.nodeCount()).as("downstream nodes of jf_sales_order").isEqualTo(41);
        assertThat(v.edges()).as("downstream edges of jf_sales_order").hasSize(49);
        assertThat(v.truncated()).isFalse();
    }

    @Test
    void upstreamSourceFromSalesOrderIsReachable() {
        MetaAsset r = hub();
        LineageView v = lineage.trace(r.getName(), r.getId(), Direction.UPSTREAM, 10);
        assertThat(v.direction()).isEqualTo("UPSTREAM");
        assertThat(v.nodeCount()).as("upstream nodes of jf_sales_order").isEqualTo(15);
        assertThat(v.edges()).as("upstream edges of jf_sales_order").hasSize(22);
    }

    /** Review N-6 regression: the canonical (depth, node, edge) pick makes repeated traces byte-equal. */
    @Test
    void repeatedTracesAreByteIdenticalIncludingTheTreePairing() {
        MetaAsset r = hub();
        for (Direction d : Direction.values()) {
            LineageView a = lineage.trace(r.getName(), r.getId(), d, 10);
            LineageView b = lineage.trace(r.getName(), r.getId(), d, 10);
            assertThat(b.nodes()).as("nodes stable for " + d).isEqualTo(a.nodes());
            assertThat(b.edges()).as("edges stable for " + d).isEqualTo(a.edges());
        }
    }

    @Test
    void everyNodeIsRealAndEveryEdgeEndpointLiesInsideTheSubgraph() {
        MetaAsset r = hub();
        for (Direction d : Direction.values()) {
            LineageView v = lineage.trace(r.getName(), r.getId(), d, 10);
            Set<Long> ids = new HashSet<>();
            for (LineageNode n : v.nodes()) {
                assertThat(n.name()).isNotBlank();
                assertThat(assetRepo.findById(n.assetId()))
                        .as("every node resolves in the catalog (zero fabrication)").isPresent();
                ids.add(n.assetId());
            }
            // distinct-node invariant: path-based cycle pruning ⇒ nodeCount == number of distinct ids
            assertThat(ids).as("nodes are distinct (cycle-safe)").hasSize(v.nodeCount());
            // closed subgraph: the induced edge set contains ONLY relations whose BOTH endpoints
            // are among the visited nodes (never a dangling half-edge) — determinism by construction
            for (LineageEdge e : v.edges()) {
                assertThat(ids).as("edge from-endpoint inside subgraph").contains(e.fromAssetId());
                assertThat(ids).as("edge to-endpoint inside subgraph").contains(e.toAssetId());
            }
        }
    }

    @Test
    void maxDepthIsClampedToTheHardCeiling() {
        MetaAsset r = hub();
        LineageView v = lineage.trace(r.getName(), r.getId(), Direction.DOWNSTREAM, 999);
        assertThat(v.maxDepth()).isEqualTo(10);
    }
}
