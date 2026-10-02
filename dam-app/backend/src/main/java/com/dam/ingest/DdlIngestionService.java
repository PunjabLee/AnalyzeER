package com.dam.ingest;

import com.dam.domain.MetaAsset;
import com.dam.domain.MetaColumn;
import com.dam.domain.MetaSource;
import com.dam.parser.DdlParser;
import com.dam.parser.DdlParserResult;
import com.dam.parser.ParsedColumn;
import com.dam.parser.ParsedTable;
import com.dam.parser.SqlSourceLocator;
import com.dam.repository.MetaAssetRepository;
import com.dam.repository.MetaColumnRepository;
import com.dam.repository.MetaSourceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Ingests a MySQL DDL file into dam_meta (structure only; relations come later, M1).
 * Idempotent per run: clears existing assets/columns before re-import (POC simplicity).
 */
@Service
public class DdlIngestionService {

    private static final Logger log = LoggerFactory.getLogger(DdlIngestionService.class);
    // Navicat-obfuscated families: N{7 hex}_ (Quartz) / P{7 hex}_ (Activiti); hex is uppercase in the dump
    private static final Pattern HEX_FAMILY = Pattern.compile("^[NP][0-9A-Fa-f]{7}_");
    // C-grade backups carry a 14-digit timestamp: _bak_yyyyMMddHHmmss (er-model 00-总览 §三)
    private static final Pattern BAK_DATE = Pattern.compile("_bak_\\d{6,}$");
    private static final Pattern COPY = Pattern.compile("_copy\\d*$");

    private final MetaSourceRepository sourceRepo;
    private final MetaAssetRepository assetRepo;
    private final MetaColumnRepository columnRepo;

    @Value("${dam.ingest.schema:test_erp}")
    private String schema;

    public DdlIngestionService(MetaSourceRepository sourceRepo,
                               MetaAssetRepository assetRepo,
                               MetaColumnRepository columnRepo) {
        this.sourceRepo = sourceRepo;
        this.assetRepo = assetRepo;
        this.columnRepo = columnRepo;
    }

    @Transactional
    public IngestReport ingestPath(String sqlPath) {
        Path resolved = SqlSourceLocator.locate(sqlPath);
        List<String> lines;
        try {
            lines = Files.readAllLines(resolved, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read DDL: " + resolved, e);
        }

        DdlParserResult result = DdlParser.parse(lines);
        log.info("Parsed {} (uniq) tables from {}", result.getUniqueTableCount(), resolved.getFileName());

        // POC: reset then rebuild
        columnRepo.deleteAllInBatch();
        assetRepo.deleteAllInBatch();

        MetaSource src = new MetaSource();
        src.setName(resolved.getFileName().toString());
        src.setType("DDL");
        src.setImportedAt(Instant.now());
        src.setTableCount(result.getUniqueTableCount());
        src.setColumnCount(result.totalColumns());
        sourceRepo.save(src);

        List<MetaColumn> colBatch = new ArrayList<>();
        for (ParsedTable t : result.getTables()) {
            MetaAsset asset = new MetaAsset();
            asset.setAssetUrn("mysql:" + schema + ":" + t.getName());
            asset.setName(t.getName());
            asset.setSchemaName(schema);
            asset.setPrefixFamily(prefixFamily(t.getName()));
            asset.setHasPk(t.isHasPk());
            asset.setTableComment(cut(t.getTableComment(), 500));
            asset.setCharset(cut(t.getCharset(), 64));
            asset.setCollate(cut(t.getCollate(), 64));
            asset.setColumnCount(t.getColumnCount());
            asset.setSourceId(src.getId());
            asset = assetRepo.save(asset);
            Long assetId = asset.getId();

            int ord = 0;
            for (ParsedColumn pc : t.getColumns()) {
                MetaColumn mc = new MetaColumn();
                mc.setAssetId(assetId);
                mc.setOrdinal(ord++);
                mc.setName(cut(pc.getName(), 200));
                mc.setType(cut(pc.getType(), 200));
                mc.setNullable(pc.isNotNull() ? "N" : "Y");
                mc.setDefaultVal(cut(pc.getDefaultValue(), 200));
                mc.setKeyHint(keyHint(pc));
                mc.setMeaning(cut(pc.getComment(), 1000));
                mc.setSourceLayer("physical");
                colBatch.add(mc);
            }
            if (colBatch.size() >= 2000) {
                columnRepo.saveAll(colBatch);
                colBatch.clear();
            }
        }
        if (!colBatch.isEmpty()) {
            columnRepo.saveAll(colBatch);
        }

        IngestReport report = new IngestReport();
        report.setSource(src.getName());
        report.setTableCount(result.getUniqueTableCount());
        report.setRawCreateCount(result.getRawCreateCount());
        report.setTotalColumns(result.totalColumns());
        log.info("Ingested: {}", report);
        return report;
    }

    private static String prefixFamily(String name) {
        if (HEX_FAMILY.matcher(name).find()) {
            return name.substring(0, 8) + "(hex)";
        }
        if (BAK_DATE.matcher(name).find()) {
            return "*_bak(date)";
        }
        if (COPY.matcher(name).find()) {
            return "*_copy";
        }
        if (!name.contains("_")) {
            return "(no-prefix)";
        }
        return name.split("_")[0] + "_";
    }

    private static String keyHint(ParsedColumn pc) {
        StringBuilder sb = new StringBuilder();
        if (pc.isPrimaryKey()) {
            sb.append("PK");
        }
        if (pc.isAutoIncrement()) {
            if (sb.length() > 0) {
                sb.append(",");
            }
            sb.append("AUTO");
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    private static String cut(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}
