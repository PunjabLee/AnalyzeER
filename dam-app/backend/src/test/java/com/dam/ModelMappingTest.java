package com.dam;

import com.dam.domain.MetaAsset;
import com.dam.domain.ModelBom;
import com.dam.domain.ModelPdm;
import com.dam.model.ModelService;
import com.dam.repository.MetaAssetRepository;
import com.dam.repository.ModelBomRepository;
import com.dam.repository.ModelPdmRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * M2 exit-criteria guard "三级映射可视化" (capability M4): the three-level model is built from the
 * authoritative 05 §二 三分类 and every BOM→LDM→PDM mapping is name-verified against the real
 * physical catalog. Crucially this asserts the no-fabrication rule: no model node references a table
 * that does not exist in meta_asset, and the core master-data object 贸易商 (jf_trader) traces to a
 * real asset.
 */
@SpringBootTest
@AutoConfigureMockMvc
@WithMockUser(roles = "ADMIN")
class ModelMappingTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    ModelService modelService;
    @Autowired
    ModelBomRepository bomRepo;
    @Autowired
    ModelPdmRepository pdmRepo;
    @Autowired
    MetaAssetRepository assetRepo;

    @BeforeEach
    void buildModel() {
        // deterministic rebuild from 05 §二 + the (auto-)ingested catalog
        modelService.build(null);
    }

    @Test
    void threeCategoriesArePopulated() {
        Set<String> categories = bomRepo.findAll().stream()
                .map(ModelBom::getCategory).collect(Collectors.toSet());
        assertThat(categories).contains("MASTER", "TRANSACTIONAL", "CONFIG");
    }

    @Test
    void traderBusinessObjectTracesToRealPhysicalAsset() throws Exception {
        ModelBom trader = bomRepo.findAll().stream()
                .filter(b -> "MASTER".equals(b.getCategory()) && "jf_trader".equals(b.getName()))
                .findFirst().orElseThrow();
        mvc.perform(get("/api/model/bom/" + trader.getId() + "/trace"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.category").value("MASTER"))
                .andExpect(jsonPath("$.nodes[0].ldmName").value("jf_trader"))
                .andExpect(jsonPath("$.nodes[0].resolved").value(true))
                .andExpect(jsonPath("$.nodes[0].assetId").isNumber());
    }

    @Test
    void everyPdmNodeBacksAnExistingAsset_noFabrication() {
        Set<Long> assetIds = assetRepo.findAllByOrderByNameAsc().stream()
                .map(MetaAsset::getId).collect(Collectors.toSet());
        List<ModelPdm> pdms = pdmRepo.findAll();
        assertThat(pdms).isNotEmpty();
        assertThat(pdms).allSatisfy(p -> assertThat(assetIds).contains(p.getAssetId()));
    }

    @Test
    void overviewReportsConsistentCounts() throws Exception {
        mvc.perform(get("/api/model/overview"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bomTotal").value((int) bomRepo.count()))
                .andExpect(jsonPath("$.pdmTotal").value((int) pdmRepo.count()))
                .andExpect(jsonPath("$.bomByCategory.MASTER").isNumber());
    }
}
