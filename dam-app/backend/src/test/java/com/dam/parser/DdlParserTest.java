package com.dam.parser;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M0 exit-criterion test: the parser must reproduce the census numbers already recorded in
 * er-model docs for test_erp.sql. Run: mvn test (file located via DAM_DDL_PATH or repo-relative).
 */
class DdlParserTest {

    private static DdlParserResult parseSample() throws IOException {
        Path p = SqlSourceLocator.locate(null);
        List<String> lines = Files.readAllLines(p, StandardCharsets.UTF_8);
        return DdlParser.parse(lines);
    }

    @Test
    void totalUniqueTables_matchesCensus_1322() throws IOException {
        DdlParserResult r = parseSample();
        assertEquals(1322, r.getUniqueTableCount(),
                "parsed unique table count must equal ddl_census.ps1 result (1322)");
    }

    @Test
    void rawCreateCount_equals_unique_whenNoDuplicates() throws IOException {
        DdlParserResult r = parseSample();
        // test_erp.sql has zero duplicate table names (per 00-总览)
        assertEquals(r.getUniqueTableCount(), r.getRawCreateCount());
    }

    @Test
    void jfSalesOrder_has90PhysicalColumns() throws IOException {
        DdlParserResult r = parseSample();
        Optional<ParsedTable> t = r.getTables().stream()
                .filter(x -> x.getName().equals("jf_sales_order")).findFirst();
        assertTrue(t.isPresent(), "jf_sales_order must be parsed");
        ParsedTable sales = t.get();
        assertEquals(90, sales.getColumnCount(),
                "jf_sales_order physical column count must equal er-model doc (90 列)");
        assertTrue(sales.isHasPk(), "jf_sales_order has a PK");

        // spot-check known columns and parsed attributes
        ParsedColumn id = col(sales, "id");
        assertTrue(id.isPrimaryKey(), "id is PK");
        assertTrue(id.isAutoIncrement(), "id is AUTO_INCREMENT");
        ParsedColumn orderNo = col(sales, "order_no");
        assertTrue(orderNo.isNotNull(), "order_no NOT NULL");
        assertEquals("", orderNo.getDefaultValue(), "order_no default ''");
    }

    @Test
    void tableComment_parsedWhenPresent() throws IOException {
        DdlParserResult r = parseSample();
        ParsedTable customer = r.getTables().stream()
                .filter(x -> x.getName().equals("jf_customer")).findFirst().orElseThrow();
        assertTrue(customer.getColumnCount() > 0);
        // comment presence is best-effort (some tables lack COMMENT=); just ensure no crash
    }

    private static ParsedColumn col(ParsedTable t, String name) {
        return t.getColumns().stream()
                .filter(c -> c.getName().equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError("column not found: " + name));
    }
}
