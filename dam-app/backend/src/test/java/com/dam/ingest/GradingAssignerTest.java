package com.dam.ingest;

import com.dam.parser.DdlParser;
import com.dam.parser.DdlParserResult;
import com.dam.parser.ErModelCensusParser;
import com.dam.parser.ErModelSourceLocator;
import com.dam.parser.ParsedDomain;
import com.dam.parser.SqlSourceLocator;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * M1 exit criterion (labels): applying the documented bucket rules over the real DDL table
 * names must yield exactly A=349 / B=853 / C=120 with zero unassigned (er-model §五 数量校验).
 */
class GradingAssignerTest {

    @Test
    void full_catalog_assignment_matches_documented_counts() throws IOException {
        Path ddl = SqlSourceLocator.locate(null);
        DdlParserResult parsed = DdlParser.parse(Files.readAllLines(ddl, StandardCharsets.UTF_8));

        Path erDir = ErModelSourceLocator.locateDir(null);
        List<ParsedDomain> domains = ErModelCensusParser.parse(
                Files.readAllLines(erDir.resolve("00-总览与分组清单.md"), StandardCharsets.UTF_8));
        Map<String, String> aIndex = new HashMap<>();
        domains.forEach(d -> d.getTables().forEach(t -> aIndex.put(t, d.getCode())));

        int a = 0;
        int b = 0;
        int c = 0;
        int un = 0;
        for (var t : parsed.getTables()) {
            GradingAssigner.Assignment as = GradingAssigner.assign(t.getName(), aIndex);
            switch (String.valueOf(as.grading())) {
                case "A" -> a++;
                case "B" -> b++;
                case "C" -> c++;
                default -> un++;
            }
        }
        assertEquals(1322, a + b + c + un, "total coverage");
        assertEquals(349, a, "A grade (332 jf_ + 17 OT)");
        assertEquals(853, b, "B grade (450 lcap + 275 N-hex + 128 P-hex)");
        assertEquals(120, c, "C grade (117 bak + 3 test)");
        assertEquals(0, un, "every table must land in a bucket");
    }

    @Test
    void boundary_rules() {
        Map<String, String> idx = Map.of(
                "jf_sales_order", "D01",
                "jf_sales_order_copy1", "D01",
                "jf_goods_bak", "D09");
        // timestamped backup wins over A membership (documented口径)
        assertEquals("C", GradingAssigner.assign("trader_bak_20251205125025", idx).grading());
        assertEquals("C", GradingAssigner.assign("test_jf_sales_order", idx).grading());
        // _bak without timestamp stays A (explicit list)
        assertEquals("A", GradingAssigner.assign("jf_goods_bak", idx).grading());
        assertEquals("A", GradingAssigner.assign("jf_sales_order", idx).grading());
        // platform families -> B
        assertEquals("B", GradingAssigner.assign("lcap_user", idx).grading());
        assertEquals("B", GradingAssigner.assign("N0DD15FF_QRTZ_CRON_TRIGGERS", idx).grading());
        assertEquals("B", GradingAssigner.assign("P3B9E1A2_TASK_RU", idx).grading());
        // unknown -> unassigned (must not happen for the real catalog, asserted above)
        assertEquals(null, GradingAssigner.assign("mystery_table", idx).grading());
    }
}
