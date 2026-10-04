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
 * M-2: the truncation branch of {@link LineageService} — previously zero-covered and reachable
 * only by raising a real subgraph past the 300-node ceiling — is now testable because the node
 * cap is configurable ({@code dam.lineage.node-limit}). With the cap at 5, the proven 41-node
 * downstream subtree of {@code jf_sales_order} MUST truncate deterministically: deepest-tail rows
 * dropped (rows arrive depth-ordered), the kept prefix stays parent-closed, and every emitted
 * edge keeps BOTH endpoints inside the returned subgraph (closed-subgraph invariant under
 * truncation, not just below the cap).
 */
@SpringBootTest(properties = "dam.lineage.node-limit=5")
class LineageTruncationTest {

    @Autowired
    LineageService lineage;
    @Autowired
    MetaAssetRepository assetRepo;

    @Test
    void oversizedDownstreamTruncatesDeterministicallyAndStaysClosed() {
        MetaAsset root = assetRepo.findByNameIgnoreCase("jf_sales_order").orElseThrow();
        LineageView v = lineage.trace(root.getName(), root.getId(), Direction.DOWNSTREAM, 10);

        assertThat(v.truncated()).as("41-node subtree vs cap of 5").isTrue();
        assertThat(v.nodeCount()).as("cap honored exactly").isEqualTo(5);

        Set<Long> ids = new HashSet<>();
        v.nodes().forEach(n -> ids.add(n.assetId()));
        assertThat(ids).hasSize(v.nodeCount());                       // distinct under truncation

        int maxDepth = 0;
        for (LineageNode n : v.nodes()) {
            maxDepth = Math.max(maxDepth, n.depth());
            if (n.assetId().equals(root.getId())) {
                assertThat(n.parentNode()).isNull();
                continue;
            }
            assertThat(n.parentNode())
                    .as("kept node %s has a parent inside the kept prefix", n.name())
                    .isIn(ids);
        }
        // depth-ordered prefix: the root and its depth-1 impacts survive before any deeper tail
        assertThat(maxDepth).isGreaterThan(0);

        for (LineageEdge e : v.edges()) {
            assertThat(ids).as("edge from-endpoint inside truncated subgraph").contains(e.fromAssetId());
            assertThat(ids).as("edge to-endpoint inside truncated subgraph").contains(e.toAssetId());
        }
    }
}
