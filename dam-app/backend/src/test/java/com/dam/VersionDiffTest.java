package com.dam;

import com.dam.domain.MetaAsset;
import com.dam.domain.MetaColumn;
import com.dam.repository.MetaAssetRepository;
import com.dam.repository.MetaColumnRepository;
import com.dam.repository.MetaVersionItemRepository;
import com.dam.repository.MetaVersionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * M2 exit-criteria guard "版本快照可比对" (R7 / adopted D3): a snapshot diffs against its
 * predecessor purely by stable asset_urn, producing ADDED / DROPPED / RETAINED / CHANGED and,
 * for changed assets, column-level deltas (加列/删列/改类型). Uses an isolated probe asset so
 * the real 1322-table catalog is never mutated.
 */
@SpringBootTest
@AutoConfigureMockMvc
class VersionDiffTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String PROBE_URN = "mysql:test_erp:zz_version_probe";

    @Autowired
    MockMvc mvc;
    @Autowired
    MetaAssetRepository assetRepo;
    @Autowired
    MetaColumnRepository columnRepo;
    @Autowired
    MetaVersionRepository versionRepo;
    @Autowired
    MetaVersionItemRepository itemRepo;

    /**
     * Each test starts from an empty version store so its first snapshot is a genuine FULL
     * baseline regardless of method execution order (tests share one cached context/DB), and
     * any probe left behind by a previous partial run is removed before we count the baseline.
     */
    @BeforeEach
    void resetVersionStore() {
        itemRepo.deleteAllInBatch();
        versionRepo.deleteAllInBatch();
        assetRepo.findByAssetUrn(PROBE_URN).ifPresent(p -> {
            columnRepo.deleteAll(columnRepo.findByAssetIdOrderByOrdinalAsc(p.getId()));
            assetRepo.delete(p);
        });
    }

    private long baselineCount() {
        return assetRepo.count();
    }

    private long snapshot(String note) throws Exception {
        String body = mvc.perform(post("/api/versions/snapshot")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\":\"" + note + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return MAPPER.readTree(body).get("id").asLong();
    }

    private MetaAsset createProbe(String colType) {
        MetaAsset a = new MetaAsset();
        a.setAssetUrn(PROBE_URN);
        a.setName("zz_version_probe");
        a.setGrading("C");
        a.setDomainCode("OT");
        a.setColumnCount(1);
        MetaAsset saved = assetRepo.save(a);
        MetaColumn c = new MetaColumn();
        c.setAssetId(saved.getId());
        c.setOrdinal(0);
        c.setName("probe_col");
        c.setType(colType);
        c.setNullable("Y");
        columnRepo.save(c);
        return saved;
    }

    @Test
    @WithMockUser(roles = "STEWARD")
    void snapshotsDiffByUrnAcrossFullLifecycle() throws Exception {
        long realCount = baselineCount();

        // v1: first snapshot of the (pre-populated) store -> FULL baseline, everything ADDED
        long v1 = snapshot("baseline");
        mvc.perform(get("/api/versions/" + v1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.baselineType").value("FULL"))
                .andExpect(jsonPath("$.assetCount").value((int) realCount))
                .andExpect(jsonPath("$.changeSummary.ADDED").value((int) realCount));

        // add a probe asset -> v2 marks exactly it ADDED, rest RETAINED
        MetaAsset probe = createProbe("int");
        long v2 = snapshot("add-probe");
        mvc.perform(get("/api/versions/" + v2))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.baselineType").value("INCREMENT"))
                .andExpect(jsonPath("$.changeSummary.ADDED").value(1))
                .andExpect(jsonPath("$.changeSummary.RETAINED").value((int) realCount));

        // change the probe column type -> v3 marks it CHANGED with a column delta
        List<MetaColumn> cols = columnRepo.findByAssetIdOrderByOrdinalAsc(probe.getId());
        cols.get(0).setType("varchar(64)");
        columnRepo.save(cols.get(0));
        long v3 = snapshot("change-probe");
        mvc.perform(get("/api/versions/" + v3))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.changeSummary.CHANGED").value(1));
        mvc.perform(get("/api/versions/" + v3 + "/changes"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].assetUrn").value(PROBE_URN))
                .andExpect(jsonPath("$[0].changedColumns[0]")
                        .value(org.hamcrest.Matchers.containsString("probe_col")));

        // delete the probe -> v4 marks it DROPPED
        columnRepo.deleteAll(columnRepo.findByAssetIdOrderByOrdinalAsc(probe.getId()));
        assetRepo.delete(probe);
        long v4 = snapshot("drop-probe");
        mvc.perform(get("/api/versions/" + v4))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.changeSummary.DROPPED").value(1))
                .andExpect(jsonPath("$.changeSummary.RETAINED").value((int) realCount))
                .andExpect(jsonPath("$.assetCount").value((int) realCount));

        // list is newest-first (v4 at the head)
        mvc.perform(get("/api/versions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value((int) v4));

        // clean up the extra snapshots (idempotent seed-free store)
        // (leaves version rows; harmless to other tests since none assert on meta_version)
    }

    @Test
    @WithMockUser(roles = "STEWARD")
    void snapshotOfEmptyChangeGivesAllRetained() throws Exception {
        long realCount = baselineCount();
        snapshot("seed-a");
        long vB = snapshot("seed-b-no-change");
        // with no catalog mutation between, everything is RETAINED, nothing added/dropped/changed
        mvc.perform(get("/api/versions/" + vB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.changeSummary.RETAINED").value((int) baselineCount()));
    }
}
