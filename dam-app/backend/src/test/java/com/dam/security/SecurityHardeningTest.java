package com.dam.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * M-4 authorization-chain guards (owner-approved 口径 2026-10-04): the filter chain is
 * fail-CLOSED — reads (GET/HEAD /api/**) stay anonymous by POC policy, every write keeps its
 * role gate, CORS preflight passes without a token, and ANY path outside the explicit whitelist
 * (the old {@code anyRequest().permitAll()} hole) is now refused outright.
 */
@SpringBootTest
@AutoConfigureMockMvc
class SecurityHardeningTest {

    @Autowired
    MockMvc mvc;

    @Test
    void anonymousReadsStayOpenPerPocPolicy() throws Exception {
        mvc.perform(get("/api/lineage").param("asset", "jf_sales_order"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/lineage/impact").param("asset", "jf_sales_order"))
                .andExpect(status().isOk());
        mvc.perform(head("/api/lineage").param("asset", "jf_sales_order"))
                .andExpect(status().isOk());
    }

    @Test
    void anonymousWritesAreRefusedOnAnyApiPath() throws Exception {
        // mapped write endpoint…
        mvc.perform(post("/api/ingest/relations"))
                .andExpect(status().isForbidden());
        // …and a path with no controller at all: the role rule fires BEFORE mapping, fail-closed
        mvc.perform(post("/api/whatever-ghost"))
                .andExpect(status().isForbidden());
    }

    @Test
    void pathsOutsideTheWhitelistFailClosed() throws Exception {
        // the pre-M-4 fallback was permitAll: these answered 200/404 openly. denyAll now refuses.
        mvc.perform(get("/actuator/health")).andExpect(status().isForbidden());
        mvc.perform(get("/")).andExpect(status().isForbidden());
        mvc.perform(get("/some/future/endpoint")).andExpect(status().isForbidden());
    }

    @Test
    void corsPreflightNeedsNoToken() throws Exception {
        // preflight arrives WITHOUT Authorization; the OPTIONS permitAll + CORS config answers 200
        // — proving the auth layer never challenges it (pre-permit it would die as 403 denyAll)
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .options("/api/ingest/relations")
                        .header("Origin", "http://localhost:5173")
                        .header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isOk());
    }

    @Test
    void adminTokenOpensTheIngestWrite() throws Exception {
        String login = mvc.perform(post("/api/auth/login")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"change-me-POC\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String token = com.fasterxml.jackson.databind.json.JsonMapper.builder().build()
                .readTree(login).get("token").asText();

        // idempotent by design (C-2): running the real chain inside the test costs nothing
        mvc.perform(post("/api/ingest/relations").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }
}
