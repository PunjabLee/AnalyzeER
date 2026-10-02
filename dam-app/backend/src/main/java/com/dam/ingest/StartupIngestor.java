package com.dam.ingest;

import com.dam.repository.MetaAssetRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Optionally ingests the default DDL on startup so the M0 POC is browsable immediately.
 * Enabled via dam.ingest.auto-on-startup=true (set for the h2 profile).
 */
@Configuration
@ConditionalOnProperty(name = "dam.ingest.auto-on-startup", havingValue = "true")
public class StartupIngestor {

    private static final Logger log = LoggerFactory.getLogger(StartupIngestor.class);

    @Value("${dam.ingest.default-sql-path:}")
    private String defaultSqlPath;

    @Bean
    ApplicationRunner autoIngest(DdlIngestionService service,
                                 ErModelLabelsIngestionService labelsService,
                                 RelationIngestionService relationService,
                                 MetaAssetRepository assetRepo,
                                 com.dam.repository.MetaRelationRepository relRepo) {
        return args -> {
            if (assetRepo.count() == 0) {
                log.info("auto-on-startup ingest enabled and catalog empty -> ingesting default DDL");
                try {
                    IngestReport report = service.ingestPath(defaultSqlPath);
                    log.info("Startup ingest done: {}", report);
                } catch (RuntimeException e) {
                    log.warn("Startup ingest skipped: {}", e.getMessage());
                }
            }
            // M1: (re)apply A/B/C + domain labels when assets exist but labels are missing
            try {
                boolean labelsMissing = assetRepo.findAllByOrderByNameAsc().stream()
                        .limit(50).anyMatch(a -> a.getGrading() == null);
                if (assetRepo.count() > 0 && labelsMissing) {
                    log.info("Applying er-model labels (grading/domain)...");
                    log.info("Labels ingest done: {}", labelsService.ingest(null));
                }
            } catch (RuntimeException e) {
                log.warn("Labels ingest skipped: {}", e.getMessage());
            }
            // M1: FK[...] edges from the logical model (relation channel) when the edge store is empty
            try {
                if (assetRepo.count() > 0 && relRepo.count() == 0) {
                    log.info("Ingesting relations from 03-逻辑数据模型...");
                    log.info("Relation ingest done: {}", relationService.ingest(null));
                }
            } catch (RuntimeException e) {
                log.warn("Relation ingest skipped: {}", e.getMessage());
            }
        };
    }
}
