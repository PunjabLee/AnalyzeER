package com.dam;

import com.dam.lineage.ImpactExportService;
import com.dam.lineage.ImpactExportService.ImpactReport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * M3 exit criteria "变更影响清单可导出": the DOWNSTREAM subtree of {@code jf_sales_order} flattened
 * into an auditable impact list (root excluded), exportable as CSV/JSON/YAML. Same ingested H2
 * corpus as the lineage tests, so numbers mirror the live trace exactly.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ImpactExportTest {

    @Autowired
    ImpactExportService export;
    @Autowired
    MockMvc mvc;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void impactListIsRootFreePathAnchoredAndRelationBacked() {
        ImpactReport r = export.build("jf_sales_order", 6);
        assertThat(r.rootAsset()).isEqualTo("jf_sales_order");
        assertThat(r.impactedCount()).isEqualTo(r.rows().size());
        assertThat(r.rows()).isNotEmpty();
        // the root itself is never an "impacted" row; the proven depth-1 impact is
        assertThat(r.rows()).noneMatch(row -> row.affectedAsset().equalsIgnoreCase("jf_sales_order"));
        assertThat(r.rows()).anySatisfy(row -> {
            assertThat(row.affectedAsset()).isEqualTo("jf_pay_request");
            assertThat(row.depth()).isEqualTo(1);
            assertThat(row.viaParent()).isEqualTo("jf_sales_order");
            assertThat(row.viaColumn()).isNotBlank();          // every propagation is an FK-backed edge
            assertThat(row.origin()).isIn("逻辑FK列", "ER");
        });
        // every row: depth ≥ 1, path anchored at the root, affected asset real
        r.rows().forEach(row -> {
            assertThat(row.depth()).isGreaterThanOrEqualTo(1);
            assertThat(row.impactPath()).startsWith("jf_sales_order → ");
            assertThat(row.impactPath()).endsWith(row.affectedAsset());
            assertThat(row.viaColumn()).isNotBlank();           // every propagation rides an FK column
        });
    }

    @Test
    void csvJsonYamlViewsStayConsistentWithTheReport() throws Exception {
        ImpactReport r = export.build("jf_sales_order", 6);

        String csv = export.toCsv(r);
        assertThat(csv).startsWith("\uFEFF");                                   // Excel-ready BOM
        assertThat(csv.substring(1)).startsWith("affected_asset,domain_code,");
        long dataLines = csv.lines().filter(l -> !l.isBlank()).count() - 1;      // minus header
        assertThat(dataLines).isEqualTo(r.rows().size());
        assertThat(csv).contains("jf_sales_order → jf_pay_request");            // depth-1 path inline in CSV

        JsonNode j = MAPPER.readTree(export.toJson(r));
        assertThat(j.get("rootAsset").asText()).isEqualTo("jf_sales_order");
        assertThat(j.get("impactedCount").asInt()).isEqualTo(r.rows().size());
        assertThat(j.get("rows")).hasSize(r.rows().size());

        assertThat(export.toYaml(r)).contains("affectedAsset:");
    }

    @Test
    void impactEndpointServesDownloadableCsv() throws Exception {
        mvc.perform(get("/api/lineage/impact").param("asset", "jf_sales_order").param("format", "csv"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("text/csv;charset=UTF-8"))
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.containsString("impact-jf_sales_order-d6.csv")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("jf_pay_request")));

        mvc.perform(get("/api/lineage/impact").param("asset", "jf_sales_order").param("format", "json"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(org.springframework.http.MediaType.APPLICATION_JSON))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"impactedCount\"")));
    }

    @Test
    void impactEndpointRejectsUnknownAssetAndFormat() throws Exception {
        mvc.perform(get("/api/lineage/impact").param("asset", "zz_no_table"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/lineage/impact").param("asset", "jf_sales_order").param("format", "xlsx"))
                .andExpect(status().isBadRequest());
    }
}
