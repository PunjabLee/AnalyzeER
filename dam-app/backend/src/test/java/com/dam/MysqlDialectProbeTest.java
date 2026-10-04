package com.dam;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * M-3: the cross-dialect probe conclusions that {@code LineageService}'s recursive CTE rests on,
 * pinned as executable documentation. They were discovered by hand against real MySQL 8 during
 * M3-2 (H2 MODE=MySQL runs pass regardless); this class records them in the repo.
 *
 * <p><b>Manual only</b> — requires the local {@code dam-mysql} container
 * ({@code docker start dam-mysql}; user dam/dam_pwd, schema dam_meta already ingested):
 * remove the gate and run {@code mvn test -Dtest=MysqlDialectProbeTest -Ddam.probe.mysql=true} when touching
 * the CTE SQL or upgrading MySQL.
 */
@SpringBootTest
@ActiveProfiles("mysql")
@EnabledIfSystemProperty(named = "dam.probe.mysql", matches = "true")   // 发版前手动门（N-10）：-Ddam.probe.mysql=true 即入库跑
class MysqlDialectProbeTest {

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void mysqlSupportsRecursiveCteWithExplicitColumnList() {
        Integer rows = jdbc.queryForObject(
                "WITH RECURSIVE t (n, d) AS (SELECT 1, 0 UNION ALL " +
                "SELECT n + 1, d + 1 FROM t WHERE d < 5) SELECT count(*) FROM t", Integer.class);
        assertThat(rows).isEqualTo(6);
    }

    @Test
    void mysqlRejectsCastNullAsBigintHenceTheAnchorUsesTheRootSentinel() {
        // why LineageService anchors parent_node/edge_id with :root instead of CAST(NULL AS BIGINT):
        assertThatExceptionOfType(DataAccessException.class).isThrownBy(
                () -> jdbc.queryForObject("SELECT CAST(NULL AS BIGINT)", Integer.class));
    }

    @Test
    void lineageShapeCteRunsWithRootSentinelAndLocatePruningOnRealData() {
        Long root = jdbc.queryForObject("SELECT MIN(id) FROM meta_asset", Long.class);
        assertThat(root).isNotNull();
        List<Map<String, Object>> rows = jdbc.queryForList(
                "WITH RECURSIVE lin (node, depth, parent_node, edge_id, path) AS (" +
                "  SELECT ?, 0, ?, ?, CAST(? AS CHAR(1000))" +
                "  UNION ALL" +
                "  SELECT r.from_asset_id, l.depth + 1, l.node, r.id, CONCAT(l.path, ',', r.from_asset_id)" +
                "  FROM meta_relation r JOIN lin l ON r.to_asset_id = l.node" +
                "  WHERE r.from_asset_id IS NOT NULL AND l.depth < 3" +
                "    AND LOCATE(CONCAT(',', r.from_asset_id, ','), CONCAT(',', l.path, ',')) = 0" +
                ") SELECT node, depth, parent_node, edge_id FROM lin ORDER BY depth, node, edge_id",
                root, root, root, root);
        assertThat(rows).as("anchored CTE executes on MySQL 8 against live meta_relation").isNotEmpty();
        assertThat(((Number) rows.get(0).get("depth")).longValue())
                .as("ORDER BY depth is deterministic").isEqualTo(0L);   // dialect-safe numeric compare
    }
}
