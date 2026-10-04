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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Ingests a MySQL DDL file into dam_meta (structure only; relations come from the
 * logic-model / ER channels).
 *
 * <p><b>In-place upsert, ids are stable</b> (review N-1/N-2, 2026-10-04): the old POC behavior
 * wiped {@code meta_asset}/{@code meta_column} and renumbered every row, which silently turned
 * every stored reference into a dead pointer — M2's {@code model_ldm/model_pdm.asset_id},
 * {@code glossary_term_ref.column_id}, and the {@code meta_relation} edge identity (governance
 * verdicts reset on every {@code /ddl}). Upserting by natural key keeps surrogate ids pinned,
 * so downstream references survive re-ingestion, and governance-only fields on a reused asset
 * (domain / certification / owner) are preserved as a freebie — they are never written here.
 *
 * <p>Only structure fields sourced FROM the DDL are overwritten on reuse; tables that vanish
 * from the document are evicted together with their columns (the document stays the source of
 * truth for the catalog's contents). {@code dryRun} computes the exact same diff — the impact
 * preview — and writes nothing.
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
        return ingestPath(sqlPath, false);
    }

    /** dryRun=true: full diff is computed and reported, NOTHING is written. */
    @Transactional
    public IngestReport ingestPath(String sqlPath, boolean dryRun) {
        Path resolved = SqlSourceLocator.locate(sqlPath);
        List<String> lines;
        try {
            lines = Files.readAllLines(resolved, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read DDL: " + resolved, e);
        }

        DdlParserResult result = DdlParser.parse(lines);
        log.info("Parsed {} (uniq) tables from {} (dryRun={})", result.getUniqueTableCount(),
                resolved.getFileName(), dryRun);

        // natural key: lower-cased table name within this schema (urn is built the same way)
        Map<String, MetaAsset> existingAssets = new HashMap<>();
        for (MetaAsset a : assetRepo.findAllByOrderByNameAsc()) {
            existingAssets.put(a.getName().toLowerCase(), a);
        }
        Map<Long, Map<String, MetaColumn>> existingColumns = new HashMap<>();
        for (MetaColumn c : columnRepo.findAll()) {
            existingColumns.computeIfAbsent(c.getAssetId(), k -> new HashMap<>())
                    .put(c.getName().toLowerCase(), c);
        }

        IngestReport report = new IngestReport();
        report.setDryRun(dryRun);
        MetaSource src = null;
        if (!dryRun) {
            src = new MetaSource();
            src.setName(resolved.getFileName().toString());
            src.setType("DDL");
            src.setImportedAt(Instant.now());
            src.setTableCount(result.getUniqueTableCount());
            src.setColumnCount(result.totalColumns());
            sourceRepo.save(src);
        }

        List<MetaColumn> toCreate = new ArrayList<>();
        List<MetaColumn> toUpdate = new ArrayList<>();
        List<MetaColumn> toEvict = new ArrayList<>();
        List<MetaAsset> toUpdateAssets = new ArrayList<>();
        List<MetaAsset> toEvictAssets = new ArrayList<>();
        List<MetaAsset> toCreateQueue = new ArrayList<>();

        for (ParsedTable t : result.getTables()) {
            String key = t.getName().toLowerCase();
            MetaAsset asset = existingAssets.remove(key);
            Long assetId;
            if (asset == null) {
                if (dryRun) {
                    report.setAssetsCreated(report.getAssetsCreated() + 1);
                    report.setColumnsCreated(report.getColumnsCreated() + t.getColumns().size());
                    continue;
                }
                asset = new MetaAsset();
                asset.setAssetUrn("mysql:" + schema + ":" + t.getName());
                asset.setName(t.getName());
                asset.setSchemaName(schema);
                applyStructure(asset, t, src.getId());
                asset = assetRepo.save(asset);   // id assigned here, then stays pinned forever
                assetId = asset.getId();
                report.setAssetsCreated(report.getAssetsCreated() + 1);
            } else {
                assetId = asset.getId();
                if (structureChanged(asset, t)) {
                    report.setAssetsUpdated(report.getAssetsUpdated() + 1);
                    if (!dryRun) {
                        applyStructure(asset, t, src.getId());
                        toUpdateAssets.add(asset);
                    }
                } else {
                    report.setAssetsUnchanged(report.getAssetsUnchanged() + 1);
                    if (!dryRun) {
                        // pointer to the latest import refreshes silently — it is not a
                        // structure change and must not flood the updated counter
                        asset.setSourceId(src.getId());
                    }
                }
            }

            Map<String, MetaColumn> colsOfAsset =
                    new HashMap<>(existingColumns.getOrDefault(assetId, Map.of()));
            int ord = 0;
            for (ParsedColumn pc : t.getColumns()) {
                String ck = pc.getName().toLowerCase();
                MetaColumn mc = colsOfAsset.remove(ck);   // consumed by the document
                if (mc == null) {
                    report.setColumnsCreated(report.getColumnsCreated() + 1);
                    if (!dryRun) {
                        mc = new MetaColumn();
                        mc.setAssetId(assetId);
                        fillColumn(mc, pc, ord);
                        toCreate.add(mc);
                    }
                } else if (columnChanged(mc, pc, ord)) {
                    report.setColumnsUpdated(report.getColumnsUpdated() + 1);
                    if (!dryRun) {
                        fillColumn(mc, pc, ord);
                        toUpdate.add(mc);
                    }
                } else {
                    report.setColumnsUnchanged(report.getColumnsUnchanged() + 1);
                }
                ord++;
            }
            // surviving asset, columns vanished from the document → evict the leftovers
            for (MetaColumn leftover : colsOfAsset.values()) {
                report.setColumnsEvicted(report.getColumnsEvicted() + 1);
                if (!dryRun) {
                    toEvict.add(leftover);
                }
            }
        }

        // assets not consumed → vanished from the document → evict with their columns
        for (MetaAsset gone : existingAssets.values()) {
            toEvictAssets.add(gone);
            report.setAssetsEvicted(report.getAssetsEvicted() + 1);
            int goneCols = existingColumns.getOrDefault(gone.getId(), Map.of()).size();
            report.setColumnsEvicted(report.getColumnsEvicted() + goneCols);
            if (!dryRun) {
                toEvict.addAll(existingColumns.getOrDefault(gone.getId(), Map.of()).values());
            }
        }

        if (!dryRun) {
            if (!toUpdateAssets.isEmpty()) {
                assetRepo.saveAll(toUpdateAssets);
            }
            if (!toCreate.isEmpty()) {
                columnRepo.saveAll(toCreate);
            }
            if (!toUpdate.isEmpty()) {
                columnRepo.saveAll(toUpdate);
            }
            if (!toEvict.isEmpty()) {
                columnRepo.deleteAll(toEvict);
            }
            if (!toEvictAssets.isEmpty()) {
                assetRepo.deleteAll(toEvictAssets);
            }
        }

        report.setSource(resolved.getFileName().toString());
        report.setTableCount(result.getUniqueTableCount());
        report.setRawCreateCount(result.getRawCreateCount());
        report.setTotalColumns(result.totalColumns());
        log.info("Ingested (dryRun={}): {}", dryRun, report);
        // dryRun wrote nothing at all (every branch is guarded by !dryRun) — no rollback trick needed
        return report;
    }

    private void applyStructure(MetaAsset asset, ParsedTable t, Long sourceId) {
        asset.setPrefixFamily(prefixFamily(t.getName()));
        asset.setHasPk(t.isHasPk());
        asset.setTableComment(cut(t.getTableComment(), 500));
        asset.setCharset(cut(t.getCharset(), 64));
        asset.setCollate(cut(t.getCollate(), 64));
        asset.setColumnCount(t.getColumnCount());
        if (sourceId != null) {
            asset.setSourceId(sourceId);
        }
    }

    /** DDL-derived structure fields only; governance fields and the source pointer never count. */
    private boolean structureChanged(MetaAsset asset, ParsedTable t) {
        return !Objects.equals(asset.getPrefixFamily(), prefixFamily(t.getName()))
                || !Objects.equals(asset.getHasPk(), t.isHasPk())
                || !Objects.equals(asset.getTableComment(), cut(t.getTableComment(), 500))
                || !Objects.equals(asset.getCharset(), cut(t.getCharset(), 64))
                || !Objects.equals(asset.getCollate(), cut(t.getCollate(), 64))
                || !Objects.equals(asset.getColumnCount(), t.getColumnCount());
    }

    private void fillColumn(MetaColumn mc, ParsedColumn pc, int ordinal) {
        mc.setOrdinal(ordinal);
        mc.setName(cut(pc.getName(), 200));
        mc.setType(cut(pc.getType(), 200));
        mc.setNullable(pc.isNotNull() ? "N" : "Y");
        mc.setDefaultVal(cut(pc.getDefaultValue(), 200));
        mc.setKeyHint(keyHint(pc));
        mc.setMeaning(cut(pc.getComment(), 1000));
        mc.setSourceLayer("physical");
    }

    private boolean columnChanged(MetaColumn mc, ParsedColumn pc, int ordinal) {
        return !Objects.equals(mc.getOrdinal(), ordinal)
                || !Objects.equals(mc.getType(), cut(pc.getType(), 200))
                || !Objects.equals(mc.getNullable(), pc.isNotNull() ? "N" : "Y")
                || !Objects.equals(mc.getDefaultVal(), cut(pc.getDefaultValue(), 200))
                || !Objects.equals(mc.getKeyHint(), keyHint(pc))
                || !Objects.equals(mc.getMeaning(), cut(pc.getComment(), 1000));
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
