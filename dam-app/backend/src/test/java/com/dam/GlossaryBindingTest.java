package com.dam;

import com.dam.domain.MetaAsset;
import com.dam.repository.MetaAssetRepository;
import com.dam.repository.MetaColumnRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * M2 exit-criteria guards for the business glossary (capability M3): the headline
 * requirement "术语 &lt;-&gt; jf_trader 可绑定" plus the binding-integrity rules —
 * idempotent re-bind, dangling-asset rejection (400), column/asset consistency and unbind.
 */
@SpringBootTest
@AutoConfigureMockMvc
class GlossaryBindingTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    MockMvc mvc;
    @Autowired
    MetaAssetRepository assetRepo;
    @Autowired
    MetaColumnRepository columnRepo;

    private MetaAsset asset(String name) {
        return assetRepo.findAllByOrderByNameAsc().stream()
                .filter(a -> a.getName().equalsIgnoreCase(name))
                .findFirst().orElseThrow(() -> new AssertionError(name + " 未摄取"));
    }

    private Long newTermId(String name) throws Exception {
        String body = mvc.perform(post("/api/glossary/terms")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"domainCode\":\"D08\",\"status\":\"DRAFT\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return MAPPER.readTree(body).get("id").asLong();
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void termCanBeBoundToTraderAndUnbound() throws Exception {
        Long traderId = asset("jf_trader").getId();
        Long termId = newTermId("贸易商-测试-" + System.nanoTime());

        // bind table-level
        String ref = mvc.perform(post("/api/glossary/terms/" + termId + "/refs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"assetId\":" + traderId + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.refType").value("TABLE"))
                .andExpect(jsonPath("$.assetName").value("jf_trader"))
                .andReturn().getResponse().getContentAsString();
        Long refId = MAPPER.readTree(ref).get("id").asLong();

        // idempotent: re-binding the same asset returns the same reference (no duplicate)
        mvc.perform(post("/api/glossary/terms/" + termId + "/refs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"assetId\":" + traderId + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(refId));

        // term detail reflects the reference
        mvc.perform(get("/api/glossary/terms/" + termId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.refs[0].assetName").value("jf_trader"));

        // reverse lookup finds at least this term from the asset side
        mvc.perform(get("/api/glossary/by-asset/" + traderId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(greaterThanOrEqualTo(1)));

        // unbind then gone
        mvc.perform(delete("/api/glossary/refs/" + refId)).andExpect(status().isNoContent());
        mvc.perform(get("/api/glossary/terms/" + termId + "/refs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void bindDanglingAssetIsRejected() throws Exception {
        Long termId = newTermId("悬空绑定-测试-" + System.nanoTime());
        mvc.perform(post("/api/glossary/terms/" + termId + "/refs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"assetId\":999999999}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void duplicateTermNameRejected() throws Exception {
        Long termId = newTermId("重复名-测试-" + System.nanoTime());
        // same name again -> conflict
        String dup = "{\"name\":\"重复名-哨兵-" + termId + "\",\"domainCode\":\"D08\"}";
        mvc.perform(post("/api/glossary/terms").contentType(MediaType.APPLICATION_JSON).content(dup))
                .andExpect(status().isOk());
        mvc.perform(post("/api/glossary/terms").contentType(MediaType.APPLICATION_JSON).content(dup))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(roles = "STEWARD")
    void columnReferenceMustMatchItsOwningAsset() throws Exception {
        Long termId = newTermId("列绑定校验-测试-" + System.nanoTime());
        Long traderId = asset("jf_trader").getId();
        Long otherAssetId = assetRepo.findAllByOrderByNameAsc().stream()
                .map(MetaAsset::getId).filter(id -> !id.equals(traderId)).findFirst().orElseThrow();
        Long traderColumnId = columnRepo.findByAssetIdOrderByOrdinalAsc(traderId).get(0).getId();

        // bind a column that belongs to jf_trader but claim a different (real) asset -> 400
        mvc.perform(post("/api/glossary/terms/" + termId + "/refs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"assetId\":" + otherAssetId + ",\"columnId\":" + traderColumnId + "}"))
                .andExpect(status().isBadRequest());

        // correct binding: column with its real asset (or asset omitted) -> COLUMN ref, 200
        mvc.perform(post("/api/glossary/terms/" + termId + "/refs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"columnId\":" + traderColumnId + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.refType").value("COLUMN"))
                .andExpect(jsonPath("$.assetName").value("jf_trader"));
    }

    @Test
    @WithMockUser(roles = "STEWARD")
    void bindWithNeitherAssetNorColumnRejected() throws Exception {
        Long termId = newTermId("空绑定-测试-" + System.nanoTime());
        mvc.perform(post("/api/glossary/terms/" + termId + "/refs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }
}
