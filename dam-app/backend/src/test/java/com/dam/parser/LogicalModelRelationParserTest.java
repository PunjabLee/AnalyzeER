package com.dam.parser;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M1 relation-channel extraction against the real er-model/03-逻辑数据模型 documents.
 */
class LogicalModelRelationParserTest {

    private static List<String> d01() throws IOException {
        Path dir = ErModelSourceLocator.locateDir(null);
        return Files.readAllLines(dir.resolve("03-逻辑数据模型/D01-销售订单域.md"), StandardCharsets.UTF_8);
    }

    @Test
    void extracts_edges_from_d01() throws IOException {
        List<ParsedRelation> edges = LogicalModelRelationParser.parse(d01());
        assertFalse(edges.isEmpty());
        // 39 D01 lines contain FK[, but 3 are intentionally not field rows: the legend line
        // (FK[目标表·依据]) and 2 compressed prose lines (id(PK)/...§7 style) -> exactly 36 edges
        assertEquals(36, edges.size());
    }

    @Test
    void naming_inference_edge_customer_id() throws IOException {
        List<ParsedRelation> edges = LogicalModelRelationParser.parse(d01());
        ParsedRelation e = edges.stream()
                .filter(x -> x.getFromTable().equals("jf_sales_order") && x.getFromColumn().equals("customer_id"))
                .findFirst().orElseThrow();
        assertEquals("jf_customer", e.getTargetRaw());
        assertEquals("字段命名", e.evidenceLevel());
        assertEquals(0.6, e.confidence(), 0.001);
    }

    @Test
    void comment_explicit_beats_lower_levels_and_bold_is_tolerated() throws IOException {
        List<ParsedRelation> edges = LogicalModelRelationParser.parse(d01());
        // quota_id ... COMMENT '额度id(jf_contract_quota_customer)' is 注释明示
        ParsedRelation e = edges.stream()
                .filter(x -> x.getFromColumn().equals("quota_id") && x.getTargetRaw().contains("contract_quota"))
                .findFirst().orElseThrow();
        assertEquals("注释明示", e.evidenceLevel());
        assertEquals(0.8, e.confidence(), 0.001);
    }

    @Test
    void self_reference_and_prose_targets() throws IOException {
        List<ParsedRelation> edges = LogicalModelRelationParser.parse(d01());
        Set<String> known = Set.of("jf_sales_order", "jf_customer", "jf_trader", "jf_contract_quota_customer");

        ParsedRelation self = edges.stream()
                .filter(x -> x.getFromColumn().equals("exchange_order_no"))
                .findFirst().orElseThrow();
        self.resolveTargets(self.getFromTable(), known);
        assertEquals(List.of("jf_sales_order"), self.getTargets());

        // prose target (客订备货明细) stays unresolved -> edge kept for confirmation workbench
        ParsedRelation prose = edges.stream()
                .filter(x -> x.getTargetRaw().equals("客订备货明细"))
                .findFirst().orElseThrow();
        prose.resolveTargets(prose.getFromTable(), known);
        assertTrue(prose.getTargets().isEmpty());
    }

    @Test
    void polymorphic_target_splits_candidates() throws IOException {
        Path dir = ErModelSourceLocator.locateDir(null);
        List<ParsedRelation> all = new java.util.ArrayList<>();
        try (var paths = Files.list(dir.resolve("03-逻辑数据模型"))) {
            for (Path p : paths.filter(f -> f.toString().endsWith(".md")).collect(Collectors.toList())) {
                all.addAll(LogicalModelRelationParser.parse(Files.readAllLines(p, StandardCharsets.UTF_8)));
            }
        }
        // e.g. FK[jf_reservation_stock/jf_stock_pot_replenishment·命名推断(多态)]
        assertTrue(all.size() >= 400, "cross-document edge yield, got " + all.size());
        ParsedRelation poly = all.stream()
                .filter(x -> x.getTargetRaw().equals("jf_reservation_stock/jf_stock_pot_replenishment"))
                .findFirst().orElseThrow();
        poly.resolveTargets(poly.getFromTable(), Set.of("jf_reservation_stock", "jf_stock_pot_replenishment"));
        assertEquals(2, poly.getTargets().size(), "polymorphic edge fans out to both candidates");
        assertTrue(poly.getBasisRaw().contains("多态"), "polymorphic marker kept in basis text");
    }

    @Test
    void legend_lines_are_never_parsed_as_edges() throws IOException {
        List<ParsedRelation> edges = LogicalModelRelationParser.parse(d01());
        assertTrue(edges.stream().noneMatch(x -> x.getTargetRaw().equals("目标表") || x.getTargetRaw().equals("目标")),
                "legend FK[目标表·依据] must not become an edge");
    }
}
