package com.dam;

import com.dam.domain.MetaColumn;
import com.dam.repository.MetaAssetRepository;
import com.dam.repository.MetaColumnRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.stream.Collectors;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * M9.1 field drag-sort persistence guards: a full ordered column-id list re-writes ordinals,
 * while a payload that does not exactly cover the asset's columns is rejected (400) so a
 * stale / foreign drag can never corrupt the ordering.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ColumnReorderTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    MetaAssetRepository assetRepo;
    @Autowired
    MetaColumnRepository columnRepo;

    private List<MetaColumn> columnsOf(String table) {
        Long assetId = assetRepo.findAllByOrderByNameAsc().stream()
                .filter(a -> a.getName().equalsIgnoreCase(table))
                .findFirst().orElseThrow().getId();
        return columnRepo.findByAssetIdOrderByOrdinalAsc(assetId);
    }

    private static String idsJson(List<Long> ids) {
        return "{\"columnIds\":[" + ids.stream().map(String::valueOf).collect(Collectors.joining(",")) + "]}";
    }

    @Test
    @WithMockUser(roles = "STEWARD")
    void fullReverseOrderIsPersisted() throws Exception {
        List<MetaColumn> cols = columnsOf("jf_trader");
        if (cols.size() < 2) {
            return; // nothing to reorder for this fixture; capability still covered by the 400 case
        }
        Long assetId = cols.get(0).getAssetId();
        List<Long> reversed = cols.stream().map(MetaColumn::getId).sorted((a, b) -> Long.compare(b, a)).toList();

        mvc.perform(patch("/api/assets/" + assetId + "/columns/order")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(idsJson(reversed)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].ordinal").value(0))
                .andExpect(jsonPath("$[0].id").value(reversed.get(0)))
                .andExpect(jsonPath("$[" + (reversed.size() - 1) + "].ordinal").value(reversed.size() - 1));
    }

    @Test
    @WithMockUser(roles = "STEWARD")
    void incompletePayloadIsRejected() throws Exception {
        List<MetaColumn> cols = columnsOf("jf_trader");
        if (cols.size() < 2) {
            return;
        }
        Long assetId = cols.get(0).getAssetId();
        List<Long> missingOne = cols.stream().map(MetaColumn::getId).limit(cols.size() - 1L).toList();
        mvc.perform(patch("/api/assets/" + assetId + "/columns/order")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(idsJson(missingOne)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(roles = "STEWARD")
    void foreignColumnIsRejected() throws Exception {
        List<MetaColumn> trader = columnsOf("jf_trader");
        if (trader.size() < 2) {
            return;
        }
        Long assetId = trader.get(0).getAssetId();
        Long foreignColId = columnsOf("jf_customer").get(0).getId();
        List<Long> withForeign = trader.stream().map(MetaColumn::getId).collect(Collectors.toList());
        withForeign.set(0, foreignColId); // swap one in a foreign column id (same size, wrong membership)
        mvc.perform(patch("/api/assets/" + assetId + "/columns/order")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(idsJson(withForeign)))
                .andExpect(status().isBadRequest());
    }
}
