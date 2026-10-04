package com.dam;

import com.dam.ingest.DdlIngestionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Review N-1 closure: the catalog is referenced by SURROGATE ids from FOUR tables
 * (meta_relation, meta_column, model_ldm, model_pdm, glossary_term_ref) — the old
 * "dangling == 0" smoke claim only ever checked meta_relation and silently shipped 66/66
 * dead pointers in the M2 model mappings after an asset rebuild. This test makes the
 * cross-table referential invariant an enforced gate AND regression-tests the exact
 * N-1 scenario: DDL re-ingestion must keep already-accepted M2 bindings resolvable.
 */
@SpringBootTest
@Transactional
class ReferentialIntegrityTest {

    @Autowired
    JdbcTemplate jt;
    @Autowired
    DdlIngestionService ddlService;

    private long dangling(String childTable, String childCol, String parentTable) {
        return jt.queryForObject(
                "select count(*) from " + childTable + " c left join " + parentTable
                        + " p on c." + childCol + " = p.id where c." + childCol
                        + " is not null and p.id is null", Long.class);
    }

    @Test
    void noReferenceInTheWholeStoreDanglesAfterTheFullChain() {
        assertEquals(0, dangling("meta_relation", "from_asset_id", "meta_asset"),
                "relation FROM endpoints must resolve");
        assertEquals(0, dangling("meta_relation", "to_asset_id", "meta_asset"),
                "relation TO endpoints must resolve");
        assertEquals(0, dangling("meta_column", "asset_id", "meta_asset"),
                "every column must belong to a catalogued asset");
        assertEquals(0, dangling("model_ldm", "asset_id", "meta_asset"),
                "M2 logical-model mappings must resolve (the N-1 blind spot)");
        assertEquals(0, dangling("model_pdm", "asset_id", "meta_asset"),
                "M2 physical-model mappings must resolve (the N-1 blind spot)");
        assertEquals(0, dangling("glossary_term_ref", "asset_id", "meta_asset"),
                "term bindings must resolve to assets");
        assertEquals(0, dangling("glossary_term_ref", "column_id", "meta_column"),
                "term bindings must resolve to columns");
    }

    /** The literal N-1 regression: bindings made BEFORE a re-ingest must still resolve AFTER it. */
    @Test
    void m2StyleBindingsSurviveDdlReingestUntouched() {
        Long assetId = jt.queryForObject(
                "select id from meta_asset where lower(name) = 'jf_sales_order'", Long.class);
        Long columnId = jt.queryForObject(
                "select id from meta_column where asset_id = ? order by ordinal limit 1",
                Long.class, assetId);

        jt.update("insert into model_pdm (name, asset_id, asset_urn) values (?, ?, ?)",
                "zz-n1-regression-pdm", assetId, "mysql:test_erp:jf_sales_order");
        jt.update("insert into model_ldm (name, asset_id) values (?, ?)",
                "zz-n1-regression-ldm", assetId);
        jt.update("insert into glossary_term_ref (term_id, asset_id, column_id, ref_type) "
                        + "values (?, ?, ?, ?)",
                999_999L, assetId, columnId, "COLUMN");

        ddlService.ingestPath(null);   // the very operation that used to renumber every id

        assertEquals(0, dangling("model_pdm", "asset_id", "meta_asset"));
        assertEquals(0, dangling("model_ldm", "asset_id", "meta_asset"));
        assertEquals(0, dangling("glossary_term_ref", "asset_id", "meta_asset"));
        assertEquals(0, dangling("glossary_term_ref", "column_id", "meta_column"));
        // and the bindings still point at the SAME rows (not re-created, not shifted)
        assertEquals(assetId, jt.queryForObject(
                "select asset_id from model_pdm where name = 'zz-n1-regression-pdm'", Long.class));
        assertEquals(columnId, jt.queryForObject(
                "select column_id from glossary_term_ref where term_id = 999_999", Long.class));
    }
}
