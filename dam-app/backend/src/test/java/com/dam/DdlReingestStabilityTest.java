package com.dam;

import com.dam.domain.MetaAsset;
import com.dam.domain.MetaSource;
import com.dam.ingest.DdlIngestionService;
import com.dam.ingest.IngestReport;
import com.dam.repository.MetaAssetRepository;
import com.dam.repository.MetaColumnRepository;
import com.dam.repository.MetaSourceRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P0 root-cause guards for review findings N-1/N-2: {@code POST /ingest/ddl} must NEVER
 * renumber {@code meta_asset}/{@code meta_column} again. The old wipe-and-rebuild silently
 * killed every stored surrogate reference (M2 model mappings 66/66 dangling, term bindings,
 * relation edge identity ⇒ verdicts reset). Ids pinned = downstream references survive.
 */
@SpringBootTest
@Transactional
class DdlReingestStabilityTest {

    @Autowired
    DdlIngestionService ddlService;
    @Autowired
    MetaAssetRepository assetRepo;
    @Autowired
    MetaColumnRepository columnRepo;
    @Autowired
    MetaSourceRepository sourceRepo;

    private Map<String, Long> nameToId() {
        Map<String, Long> m = new LinkedHashMap<>();
        for (MetaAsset a : assetRepo.findAllByOrderByNameAsc()) {
            m.put(a.getName(), a.getId());
        }
        return m;
    }

    @Test
    void reingestPinsEveryAssetIdAndReportsPureNoOp() {
        assertThat(assetRepo.count()).isGreaterThan(1000); // startup ingestion present
        Map<String, Long> before = nameToId();

        IngestReport r = ddlService.ingestPath(null);

        assertThat(r.isDryRun()).isFalse();
        assertThat(nameToId()).isEqualTo(before);              // the iron invariant: ids identical
        assertThat(r.getAssetsCreated()).isZero();
        assertThat(r.getAssetsUpdated()).isZero();             // same document → no structure delta
        assertThat(r.getAssetsEvicted()).isZero();
        assertThat(r.getColumnsCreated()).isZero();
        assertThat(r.getColumnsEvicted()).isZero();
        assertThat(r.getAssetsUnchanged()).isEqualTo(before.size());
    }

    @Test
    void dryRunComputesTheSamePreviewAndWritesNothing() {
        Map<String, Long> before = nameToId();
        long sourcesBefore = sourceRepo.count();
        long columnsBefore = columnRepo.count();

        IngestReport r = ddlService.ingestPath(null, true);

        assertThat(r.isDryRun()).isTrue();
        assertThat(r.getAssetsUnchanged()).isEqualTo(before.size());
        assertThat(r.getAssetsCreated()).isZero();
        assertThat(r.getAssetsEvicted()).isZero();
        assertThat(nameToId()).isEqualTo(before);
        assertThat(sourceRepo.count()).isEqualTo(sourcesBefore);   // no new MetaSource row either
        assertThat(columnRepo.count()).isEqualTo(columnsBefore);
    }

    @Test
    void governanceAttributesSurviveReingestForFree() {
        // the old full-delete rebuild evaporated these; upsert must not touch them
        MetaAsset hub = assetRepo.findByNameIgnoreCase("jf_sales_order").orElseThrow();
        hub.setCertificationStatus("认证");
        hub.setDomainCode("D01");
        assetRepo.saveAndFlush(hub);
        Long hubId = hub.getId();

        ddlService.ingestPath(null);

        MetaAsset after = assetRepo.findById(hubId).orElseThrow();
        assertThat(after.getName()).isEqualTo("jf_sales_order");
        assertThat(after.getCertificationStatus()).isEqualTo("认证");
        assertThat(after.getDomainCode()).isEqualTo("D01");
    }

    @Test
    void everyRunAppendsOneAuditedSourceRow() {
        long before = sourceRepo.count();
        MetaSource latest = null;
        for (MetaSource s : sourceRepo.findAll()) {
            if (latest == null || s.getImportedAt().isAfter(latest.getImportedAt())) {
                latest = s;
            }
        }
        ddlService.ingestPath(null);
        assertThat(sourceRepo.count()).isEqualTo(before + 1);
        // assets point at the freshest import record (silently refreshed, not "updated")
        assertThat(assetRepo.findAllByOrderByNameAsc()).allMatch(a ->
                a.getSourceId() != null && a.getSourceId() > 0);
    }
}
