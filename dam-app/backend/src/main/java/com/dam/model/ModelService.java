package com.dam.model;

import com.dam.domain.MetaAsset;
import com.dam.domain.ModelBom;
import com.dam.domain.ModelLdm;
import com.dam.domain.ModelMapping;
import com.dam.domain.ModelPdm;
import com.dam.parser.CoreEntityCatalogParser;
import com.dam.parser.CoreEntityCatalogParser.ParsedCoreEntity;
import com.dam.parser.ErModelSourceLocator;
import com.dam.repository.MetaAssetRepository;
import com.dam.repository.ModelBomRepository;
import com.dam.repository.ModelLdmRepository;
import com.dam.repository.ModelMappingRepository;
import com.dam.repository.ModelPdmRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Builds the three-level model (BOM ↔ LDM ↔ PDM) and its cross-level mappings — capability M4,
 * PLAN §3.2-(4) / §6 M2 exit-criteria "三级映射可视化".
 *
 * <p>Source of truth is the authoritative 三分类 in {@code er-model/05-跨域核心关系总览.md §二}.
 * Each row's 实体 expression is tokenised and matched <b>exactly</b> against the real physical
 * catalog ({@code meta_asset.name}); only tables that genuinely exist become LDM/PDM nodes, so a
 * mapping is always evidence-backed. Shorthand/aliases that do not resolve to a concrete table are
 * preserved verbatim on the BOM's {@code sourceExpr} and simply produce no child node — nothing is
 * invented (R4 / 评审#1 no-fabrication rule). The build is deterministic, so it fully rebuilds.
 */
@Service
public class ModelService {

    private static final Logger log = LoggerFactory.getLogger(ModelService.class);
    private static final Pattern IDENT = Pattern.compile("[A-Za-z][A-Za-z0-9_]+");
    private static final Pattern PAREN = Pattern.compile("（[^）]*）|\\([^)]*\\)");
    private static final Pattern LABEL = Pattern.compile("（([^）]*)）|\\([^)]*\\)");

    private final ModelBomRepository bomRepo;
    private final ModelLdmRepository ldmRepo;
    private final ModelPdmRepository pdmRepo;
    private final ModelMappingRepository mappingRepo;
    private final MetaAssetRepository assetRepo;

    public ModelService(ModelBomRepository bomRepo,
                        ModelLdmRepository ldmRepo,
                        ModelPdmRepository pdmRepo,
                        ModelMappingRepository mappingRepo,
                        MetaAssetRepository assetRepo) {
        this.bomRepo = bomRepo;
        this.ldmRepo = ldmRepo;
        this.pdmRepo = pdmRepo;
        this.mappingRepo = mappingRepo;
        this.assetRepo = assetRepo;
    }

    public record BuildReport(int bom, int ldm, int pdm, int mappings, int bomWithoutResolution) {
        @Override
        public String toString() {
            return "ModelBuild{bom=" + bom + ", ldm=" + ldm + ", pdm=" + pdm
                    + ", mappings=" + mappings + ", bomWithoutResolution=" + bomWithoutResolution + "}";
        }
    }

    /** (re)build the whole three-level model from er-model 05 §二 + the physical catalog. */
    @Transactional
    public BuildReport build(String erModelDir) {
        Path dir = ErModelSourceLocator.locateDir(erModelDir);
        Path file = dir.resolve("05-跨域核心关系总览.md");
        List<ParsedCoreEntity> rows;
        try {
            rows = CoreEntityCatalogParser.parse(Files.readAllLines(file, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read core-entity catalog: " + file, e);
        }

        Map<String, MetaAsset> byName = new LinkedHashMap<>();
        for (MetaAsset a : assetRepo.findAllByOrderByNameAsc()) {
            if (a.getName() != null) {
                byName.putIfAbsent(a.getName().toLowerCase(), a);
            }
        }

        // deterministic full rebuild
        mappingRepo.deleteAllInBatch();
        pdmRepo.deleteAllInBatch();
        ldmRepo.deleteAllInBatch();
        bomRepo.deleteAllInBatch();

        int unmapped = 0;
        for (ParsedCoreEntity row : rows) {
            List<MetaAsset> matched = resolve(row.expression(), byName);
            ModelBom bom = new ModelBom();
            bom.setCategory(row.category());
            bom.setDomainCode(trim(row.domainCode(), 16));
            bom.setDescription(trim(row.description(), 1000));
            bom.setSourceExpr(trim(row.expression(), 500));
            bom.setLabel(trim(extractLabel(row.expression()), 200));
            bom.setName(trim(matched.isEmpty()
                    ? firstTokenOrSelf(row.expression())
                    : matched.get(0).getName(), 200));
            ModelBom savedBom = bomRepo.save(bom);
            if (matched.isEmpty()) {
                unmapped++;
            }
            for (MetaAsset asset : matched) {
                ModelLdm ldm = ldmRepo.findByName(asset.getName()).orElseGet(() -> {
                    ModelLdm l = new ModelLdm();
                    l.setName(asset.getName());
                    l.setDomainCode(asset.getDomainCode());
                    l.setDescription(trim(row.description(), 1000));
                    l.setAssetId(asset.getId());
                    return ldmRepo.save(l);
                });
                ModelPdm pdm = pdmRepo.findByName(asset.getName()).orElseGet(() -> {
                    ModelPdm p = new ModelPdm();
                    p.setName(asset.getName());
                    p.setAssetId(asset.getId());
                    p.setAssetUrn(asset.getAssetUrn());
                    p.setColumnCount(asset.getColumnCount());
                    return pdmRepo.save(p);
                });
                if (mappingRepo.findByBomIdAndLdmId(savedBom.getId(), ldm.getId()).isEmpty()) {
                    ModelMapping m = new ModelMapping();
                    m.setBomId(savedBom.getId());
                    m.setLdmId(ldm.getId());
                    m.setPdmId(pdm.getId());
                    m.setBasis("05 §二 三分类·名称经物理目录校验");
                    mappingRepo.save(m);
                }
            }
        }

        BuildReport report = new BuildReport((int) bomRepo.count(), (int) ldmRepo.count(),
                (int) pdmRepo.count(), (int) mappingRepo.count(), unmapped);
        log.info("Three-level model built from {}: {}", file.getFileName(), report);
        return report;
    }

    /** extract ASCII identifier tokens (parenthetical display text stripped) and keep the ones
     *  that match a real catalog table, preserving order and de-duplicating by asset id. */
    private List<MetaAsset> resolve(String expression, Map<String, MetaAsset> byName) {
        List<MetaAsset> out = new ArrayList<>();
        Set<Long> seenIds = new LinkedHashSet<>();
        for (String token : tokens(expression)) {
            MetaAsset a = byName.get(token);
            if (a != null && seenIds.add(a.getId())) {
                out.add(a);
            }
        }
        return out;
    }

    private List<String> tokens(String expression) {
        String stripped = PAREN.matcher(expression.replace("*", "")).replaceAll(" ");
        List<String> out = new ArrayList<>();
        Matcher m = IDENT.matcher(stripped);
        while (m.find()) {
            out.add(m.group().toLowerCase());
        }
        return out;
    }

    private String firstTokenOrSelf(String expression) {
        List<String> t = tokens(expression);
        return t.isEmpty() ? expression.replace("*", "").trim() : t.get(0);
    }

    private static String extractLabel(String expression) {
        Matcher m = LABEL.matcher(expression);
        if (m.find()) {
            return m.group(1) != null ? m.group(1) : m.group(2);
        }
        return null;
    }

    private static String trim(String s, int max) {
        if (s == null) {
            return null;
        }
        String v = s.trim();
        return v.length() <= max ? v : v.substring(0, max);
    }
}
