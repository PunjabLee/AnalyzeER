package com.dam;

import com.dam.repository.MetaAssetRepository;
import com.dam.repository.SysUserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * D5 (identity-source convergence, PLAN R6, decision 2026-10-03) acceptance guards:
 * <ul>
 *   <li>the dam_meta {@code sys_user} table is the authoritative login source — a seeded
 *       account authenticates over BCrypt and the whole login -> JWT path works;</li>
 *   <li>governance Owner/Steward writes resolve against the same {@code sys_user} identity
 *       set, so a dangling reference is rejected as 400 (not silently persisted).</li>
 * </ul>
 *
 * <p>Relies on the startup seeders (UserSeed) having populated the three POC accounts.
 */
@SpringBootTest
@AutoConfigureMockMvc
class IdentitySourceGuardTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    SysUserRepository userRepo;
    @Autowired
    MetaAssetRepository assetRepo;

    @Test
    void seededAccountLogsInViaDbIdentitySource() throws Exception {
        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"change-me-POC\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").exists())
                .andExpect(jsonPath("$.roles[0]").value("ADMIN"));
    }

    @Test
    void wrongPasswordIsRejected() throws Exception {
        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"not-the-password\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void governancePatchRejectsDanglingOwner() throws Exception {
        Long assetId = assetRepo.findAllByOrderByNameAsc().get(0).getId();
        mvc.perform(patch("/api/assets/" + assetId + "/governance")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ownerId\": 999999999}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void governancePatchAcceptsRealOwner() throws Exception {
        Long assetId = assetRepo.findAllByOrderByNameAsc().get(0).getId();
        Long adminId = userRepo.findByUsername("admin").orElseThrow().getId();
        mvc.perform(patch("/api/assets/" + assetId + "/governance")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ownerId\": " + adminId + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ownerId").value(adminId));
    }
}
