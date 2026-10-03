package com.dam.web;

import com.dam.audit.AuditService;
import com.dam.domain.ModelBom;
import com.dam.domain.ModelLdm;
import com.dam.domain.ModelPdm;
import com.dam.model.ModelService;
import com.dam.repository.ModelBomRepository;
import com.dam.repository.ModelLdmRepository;
import com.dam.repository.ModelMappingRepository;
import com.dam.repository.ModelPdmRepository;
import com.dam.web.dto.ModelDtos.BomSummary;
import com.dam.web.dto.ModelDtos.BomTrace;
import com.dam.web.dto.ModelDtos.Overview;
import com.dam.web.dto.ModelDtos.TraceNode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Three-level model API (capability M4, PLAN §3.2-(4) / M2 exit-criteria "三级映射可视化").
 * Reads are open (GET /api/** permitAll); (re)building the model is a write and is audited.
 */
@RestController
@RequestMapping("/api/model")
public class ModelController {

    private final ModelBomRepository bomRepo;
    private final ModelLdmRepository ldmRepo;
    private final ModelPdmRepository pdmRepo;
    private final ModelMappingRepository mappingRepo;
    private final ModelService modelService;
    private final AuditService audit;

    public ModelController(ModelBomRepository bomRepo,
                           ModelLdmRepository ldmRepo,
                           ModelPdmRepository pdmRepo,
                           ModelMappingRepository mappingRepo,
                           ModelService modelService,
                           AuditService audit) {
        this.bomRepo = bomRepo;
        this.ldmRepo = ldmRepo;
        this.pdmRepo = pdmRepo;
        this.mappingRepo = mappingRepo;
        this.modelService = modelService;
        this.audit = audit;
    }

    @GetMapping("/bom")
    public List<BomSummary> bom() {
        return bomRepo.findAllByOrderByCategoryAscNameAsc().stream()
                .map(b -> new BomSummary(b.getId(), b.getName(), b.getLabel(), b.getCategory(),
                        b.getDomainCode(), mappingRepo.findByBomId(b.getId()).size()))
                .toList();
    }

    @GetMapping("/bom/{id}/trace")
    public ResponseEntity<BomTrace> trace(@PathVariable Long id) {
        ModelBom b = bomRepo.findById(id).orElse(null);
        if (b == null) {
            return ResponseEntity.notFound().build();
        }
        List<TraceNode> nodes = mappingRepo.findByBomId(id).stream().map(m -> {
            ModelLdm ldm = ldmRepo.findById(m.getLdmId()).orElse(null);
            ModelPdm pdm = m.getPdmId() == null ? null : pdmRepo.findById(m.getPdmId()).orElse(null);
            boolean resolved = pdm != null && pdm.getAssetId() != null;
            return new TraceNode(
                    ldm == null ? null : ldm.getId(),
                    ldm == null ? null : ldm.getName(),
                    ldm == null ? b.getDomainCode() : ldm.getDomainCode(),
                    pdm == null ? null : pdm.getId(),
                    pdm == null ? null : pdm.getAssetId(),
                    pdm == null ? null : pdm.getAssetUrn(),
                    pdm == null ? null : pdm.getColumnCount(),
                    m.getBasis(),
                    resolved);
        }).toList();
        return ResponseEntity.ok(new BomTrace(b.getId(), b.getName(), b.getLabel(), b.getCategory(),
                b.getDomainCode(), b.getDescription(), b.getSourceExpr(), nodes));
    }

    @GetMapping("/overview")
    public Overview overview() {
        Map<String, Integer> byCategory = new TreeMap<>();
        for (ModelBom b : bomRepo.findAll()) {
            byCategory.merge(b.getCategory(), 1, Integer::sum);
        }
        int unmapped = (int) bomRepo.findAll().stream()
                .filter(b -> mappingRepo.findByBomId(b.getId()).isEmpty()).count();
        return new Overview((int) bomRepo.count(), (int) ldmRepo.count(), (int) pdmRepo.count(),
                (int) mappingRepo.count(), unmapped, byCategory);
    }

    @PostMapping("/rebuild")
    public ResponseEntity<ModelService.BuildReport> rebuild() {
        ModelService.BuildReport report = modelService.build(null);
        audit.record("MODEL_REBUILD", "model", report.toString());
        return ResponseEntity.ok(report);
    }
}
