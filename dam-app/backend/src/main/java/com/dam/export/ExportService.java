package com.dam.export;

import com.dam.domain.MetaAsset;
import com.dam.domain.MetaColumn;
import com.dam.domain.MetaRelation;
import com.dam.repository.MetaAssetRepository;
import com.dam.repository.MetaColumnRepository;
import com.dam.repository.MetaRelationRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Lossless-ish catalog export (PLAN M1.4, "dam-meta/1" schema). JSON and YAML views of
 * assets + columns + relations so two snapshots can be diffed by asset_urn (M1 exit criteria).
 */
@Service
public class ExportService {

    public static final String SCHEMA = "dam-meta/1";

    private final MetaAssetRepository assetRepo;
    private final MetaColumnRepository columnRepo;
    private final MetaRelationRepository relRepo;
    private final ObjectMapper json = new ObjectMapper();
    private final YAMLMapper yaml = new YAMLMapper();

    public ExportService(MetaAssetRepository assetRepo,
                         MetaColumnRepository columnRepo,
                         MetaRelationRepository relRepo) {
        this.assetRepo = assetRepo;
        this.columnRepo = columnRepo;
        this.relRepo = relRepo;
    }

    public String exportJson(String domain, String grading) {
        return write(domain, grading, false);
    }

    public String exportYaml(String domain, String grading) {
        return write(domain, grading, true);
    }

    private String write(String domain, String grading, boolean asYaml) {
        List<MetaAsset> all = assetRepo.findAll();
        // id -> urn map over ALL assets so a relation's target urn resolves even when the
        // target table falls outside the exported slice (H-2: never strip a resolvable target).
        Map<Long, String> urnById = new HashMap<>();
        all.forEach(a -> urnById.put(a.getId(), a.getAssetUrn()));

        List<MetaAsset> assets = all.stream()
                .filter(a -> domain == null || domain.isBlank() || domain.equalsIgnoreCase(a.getDomainCode()))
                .filter(a -> grading == null || grading.isBlank() || grading.equalsIgnoreCase(a.getGrading()))
                .sorted((x, y) -> x.getName().compareToIgnoreCase(y.getName()))
                .toList();
        Set<Long> sliceIds = new HashSet<>();
        assets.forEach(a -> sliceIds.add(a.getId()));

        ObjectNode root = json.createObjectNode();
        root.put("schema", SCHEMA);
        root.put("generatedAt", Instant.now().toString());
        root.put("assetCount", assets.size());

        ArrayNode arr = root.putArray("assets");
        for (MetaAsset a : assets) {
            ObjectNode n = arr.addObject();
            n.put("urn", a.getAssetUrn());
            n.put("name", a.getName());
            n.put("grading", a.getGrading());
            n.put("domainCode", a.getDomainCode());
            n.put("prefixFamily", a.getPrefixFamily());
            n.put("hasPk", a.getHasPk());
            n.put("columnCount", a.getColumnCount());
            n.put("tableComment", a.getTableComment());
            n.put("certificationStatus", a.getCertificationStatus());
            n.put("sensitivityLevel", a.getSensitivityLevel());
            n.put("deprecated", a.getDeprecated());
            n.put("ownerId", a.getOwnerId());
            n.put("stewardId", a.getStewardId());
            ArrayNode cols = n.putArray("columns");
            for (MetaColumn c : columnRepo.findByAssetIdOrderByOrdinalAsc(a.getId())) {
                ObjectNode cn = cols.addObject();
                cn.put("ordinal", c.getOrdinal());
                cn.put("name", c.getName());
                cn.put("type", c.getType());
                cn.put("nullable", c.getNullable());
                cn.put("defaultVal", c.getDefaultVal());
                cn.put("keyHint", c.getKeyHint());
                cn.put("meaning", c.getMeaning());
                cn.put("sourceLayer", c.getSourceLayer());
            }
        }

        ArrayNode rels = root.putArray("relations");
        for (MetaRelation r : relRepo.findAll()) {
            // keep edges whose FROM end is inside the exported asset slice;
            // unresolved targets stay with toUrn=null + targetRaw (M5 confirmation loop)
            if (!sliceIds.contains(r.getFromAssetId())) {
                continue;
            }
            ObjectNode n = rels.addObject();
            n.put("fromUrn", urnById.get(r.getFromAssetId()));
            n.put("fromColumn", r.getFromColumn());
            if (r.getToAssetId() != null && urnById.containsKey(r.getToAssetId())) {
                n.put("toUrn", urnById.get(r.getToAssetId()));
                // target exists in the catalog but outside this slice -> explicit external flag
                n.put("externalTarget", !sliceIds.contains(r.getToAssetId()));
            } else {
                n.putNull("toUrn");
                n.put("externalTarget", false);
            }
            n.put("toColumn", r.getToColumn());
            n.put("targetRaw", r.getTargetRaw());
            n.put("evidenceLevel", r.getEvidenceLevel());
            n.put("origin", r.getOrigin());
            n.put("confidence", r.getConfidence());
            n.put("confirmStatus", r.getConfirmStatus());
            n.put("crossDomain", r.getCrossDomain());
            n.put("sourceDoc", r.getSourceDoc());
        }

        try {
            return asYaml ? yaml.writeValueAsString(root) : json.writerWithDefaultPrettyPrinter().writeValueAsString(root);
        } catch (Exception e) {
            throw new IllegalStateException("export failed", e);
        }
    }
}
