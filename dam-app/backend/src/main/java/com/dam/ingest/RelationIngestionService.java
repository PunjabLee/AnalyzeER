package com.dam.ingest;

import com.dam.domain.MetaAsset;
import com.dam.domain.MetaRelation;
import com.dam.parser.ErModelSourceLocator;
import com.dam.parser.LogicalModelRelationParser;
import com.dam.parser.ParsedRelation;
import com.dam.repository.MetaAssetRepository;
import com.dam.repository.MetaRelationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * M1 relation channel (PLAN §M1.1 双通道): edges are ingested from
 * er-model/03-逻辑数据模型/*.md "FK[目标·依据]" key cells — never from the DDL,
 * because the governed database declares zero foreign keys.
 *
 * <p>Every edge keeps the five-level evidence, a confidence seed and confirm_status=待确认
 * for the later M5 human confirmation loop. Targets named only in prose keep to_asset_id=null.
 */
@Service
public class RelationIngestionService {

    private static final Logger log = LoggerFactory.getLogger(RelationIngestionService.class);
    private static final Pattern CROSS_DOMAIN = Pattern.compile("跨域\\s*(D\\d{2})");
    private static final Pattern SELF_REF_TEXT = Pattern.compile("自关联|自引用");

    private final MetaAssetRepository assetRepo;
    private final MetaRelationRepository relRepo;

    public RelationIngestionService(MetaAssetRepository assetRepo, MetaRelationRepository relRepo) {
        this.assetRepo = assetRepo;
        this.relRepo = relRepo;
    }

    public record RelationReport(int edges, int resolved, int unresolved, int files) {
        @Override
        public String toString() {
            return "RelationReport{edges=" + edges + ", resolved=" + resolved
                    + ", unresolved=" + unresolved + ", files=" + files + "}";
        }
    }

    @Transactional
    public RelationReport ingest(String erModelDir) {
        Path dir = ErModelSourceLocator.locateDir(erModelDir);
        Path modelDir = dir.resolve("03-逻辑数据模型");

        // name(lower) -> asset, for both ends of an edge
        Map<String, MetaAsset> byName = new HashMap<>();
        for (MetaAsset a : assetRepo.findAllByOrderByNameAsc()) {
            byName.put(a.getName().toLowerCase(Locale.ROOT), a);
        }

        relRepo.deleteAllInBatch();
        List<MetaRelation> batch = new ArrayList<>();
        int files = 0;
        try (Stream<Path> paths = Files.list(modelDir)) {
            List<Path> docs = paths.filter(p -> p.toString().endsWith(".md")).sorted().toList();
            for (Path doc : docs) {
                files++;
                List<String> lines = Files.readAllLines(doc, StandardCharsets.UTF_8);
                List<ParsedRelation> parsed = LogicalModelRelationParser.parse(lines);
                String relPath = "03-逻辑数据模型/" + doc.getFileName();
                for (ParsedRelation pr : parsed) {
                    MetaAsset from = byName.get(pr.getFromTable().toLowerCase(Locale.ROOT));
                    if (from == null) {
                        log.warn("section table {} not found in catalog (doc {})", pr.getFromTable(), relPath);
                        continue;
                    }
                    pr.resolveTargets(pr.getFromTable(), byName.keySet());
                    String cross = null;
                    Matcher cd = CROSS_DOMAIN.matcher(pr.getBasisRaw() == null ? "" : pr.getBasisRaw());
                    if (cd.find()) {
                        cross = cd.group(1);
                    }
                    if (pr.getTargets().isEmpty()) {
                        // prose target: keep one unresolved edge for the confirmation workbench
                        batch.add(edge(from, pr, null, cross, relPath));
                    } else {
                        for (String t : pr.getTargets()) {
                            batch.add(edge(from, pr, byName.get(t.toLowerCase(Locale.ROOT)), cross, relPath));
                        }
                    }
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to scan " + modelDir, e);
        }
        relRepo.saveAll(batch);

        long resolved = batch.stream().filter(r -> r.getToAssetId() != null).count();
        RelationReport report = new RelationReport(batch.size(), (int) resolved,
                batch.size() - (int) resolved, files);
        log.info("Relations ingested: {}", report);
        return report;
    }

    private MetaRelation edge(MetaAsset from, ParsedRelation pr, MetaAsset to, String cross, String doc) {
        MetaRelation r = new MetaRelation();
        r.setFromAssetId(from.getId());
        r.setFromColumn(pr.getFromColumn());
        // self-reference expressed in prose (自关联) points back to the same asset
        if (to == null && SELF_REF_TEXT.matcher(pr.getTargetRaw()).find()) {
            to = from;
        }
        r.setToAssetId(to == null ? null : to.getId());
        r.setToColumn(pr.getTargetColumn());
        r.setTargetRaw(pr.getTargetRaw());
        r.setEvidenceLevel(pr.evidenceLevel());
        r.setConfidence(pr.confidence());
        r.setOrigin("逻辑FK列");
        r.setInferred(true);
        r.setConfirmStatus("待确认");
        r.setCrossDomain(cross);
        r.setBasisRaw(cut(pr.getBasisRaw(), 300));
        r.setSourceDoc(cut(doc, 200));
        return r;
    }

    private static String cut(String s, int max) {
        if (s == null || s.isEmpty()) {
            return s;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}
