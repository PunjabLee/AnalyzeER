package com.dam.web;

import com.dam.audit.AuditService;
import com.dam.domain.DictCodeValue;
import com.dam.domain.DictNamingRule;
import com.dam.domain.DictStandardField;
import com.dam.repository.DictCodeValueRepository;
import com.dam.repository.DictNamingRuleRepository;
import com.dam.repository.DictStandardFieldRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Data dictionary CRUD (PLAN §M2.1/2.2/2.4): standard fields, code values and
 * naming rules. Writes require ADMIN/STEWARD via SecurityConfig and land in the audit log.
 */
@RestController
@RequestMapping("/api/dict")
public class DictController {

    private final DictStandardFieldRepository fieldRepo;
    private final DictCodeValueRepository codeRepo;
    private final DictNamingRuleRepository namingRepo;
    private final AuditService audit;

    public DictController(DictStandardFieldRepository fieldRepo,
                          DictCodeValueRepository codeRepo,
                          DictNamingRuleRepository namingRepo,
                          AuditService audit) {
        this.fieldRepo = fieldRepo;
        this.codeRepo = codeRepo;
        this.namingRepo = namingRepo;
        this.audit = audit;
    }

    // ---- standard fields (M2.1) ------------------------------------------------------------

    @GetMapping("/standard-fields")
    public List<DictStandardField> listFields() {
        return fieldRepo.findAllByOrderByFieldNameAsc();
    }

    @PostMapping("/standard-fields")
    public DictStandardField createField(@RequestBody DictStandardField f) {
        f.setId(null);
        DictStandardField saved = fieldRepo.save(f);
        audit.record("DICT_FIELD_CREATE", saved.getFieldName(), null);
        return saved;
    }

    @PutMapping("/standard-fields/{id}")
    public ResponseEntity<DictStandardField> updateField(@PathVariable Long id,
                                                         @RequestBody DictStandardField f) {
        return fieldRepo.findById(id).map(cur -> {
            cur.setFieldName(f.getFieldName());
            cur.setDataType(f.getDataType());
            cur.setLengthVal(f.getLengthVal());
            cur.setSemantic(f.getSemantic());
            cur.setEnabled(f.getEnabled() == null || f.getEnabled());
            DictStandardField saved = fieldRepo.save(cur);
            audit.record("DICT_FIELD_UPDATE", saved.getFieldName(), null);
            return ResponseEntity.ok(saved);
        }).orElseGet(ResponseEntity.notFound()::build);
    }

    @DeleteMapping("/standard-fields/{id}")
    public ResponseEntity<Void> deleteField(@PathVariable Long id) {
        if (!fieldRepo.existsById(id)) {
            return ResponseEntity.notFound().build();
        }
        fieldRepo.deleteById(id);
        audit.record("DICT_FIELD_DELETE", "id=" + id, null);
        return ResponseEntity.noContent().build();
    }

    // ---- code values (M2.2) ------------------------------------------------------------------

    @GetMapping("/code-values")
    public List<DictCodeValue> listCodes(@RequestParam(required = false) String category) {
        return category == null || category.isBlank()
                ? codeRepo.findAllByOrderByCategoryAscOrdinalAsc()
                : codeRepo.findByCategoryOrderByOrdinalAscIdAsc(category);
    }

    @PostMapping("/code-values")
    public DictCodeValue createCode(@RequestBody DictCodeValue c) {
        c.setId(null);
        DictCodeValue saved = codeRepo.save(c);
        audit.record("DICT_CODE_CREATE", saved.getCategory() + ":" + saved.getCodeValue(), null);
        return saved;
    }

    @PutMapping("/code-values/{id}")
    public ResponseEntity<DictCodeValue> updateCode(@PathVariable Long id,
                                                    @RequestBody DictCodeValue c) {
        return codeRepo.findById(id).map(cur -> {
            cur.setCategory(c.getCategory());
            cur.setCodeValue(c.getCodeValue());
            cur.setMeaning(c.getMeaning());
            if (c.getOrdinal() != null) {
                cur.setOrdinal(c.getOrdinal());
            }
            DictCodeValue saved = codeRepo.save(cur);
            audit.record("DICT_CODE_UPDATE", saved.getCategory() + ":" + saved.getCodeValue(), null);
            return ResponseEntity.ok(saved);
        }).orElseGet(ResponseEntity.notFound()::build);
    }

    @DeleteMapping("/code-values/{id}")
    public ResponseEntity<Void> deleteCode(@PathVariable Long id) {
        if (!codeRepo.existsById(id)) {
            return ResponseEntity.notFound().build();
        }
        codeRepo.deleteById(id);
        audit.record("DICT_CODE_DELETE", "id=" + id, null);
        return ResponseEntity.noContent().build();
    }

    // ---- naming rules (M2.4) -----------------------------------------------------------------

    @GetMapping("/naming-rules")
    public List<DictNamingRule> listNaming() {
        return namingRepo.findAllByOrderByNameAsc();
    }

    @PostMapping("/naming-rules")
    public DictNamingRule createNaming(@RequestBody DictNamingRule r) {
        r.setId(null);
        validateRegex(r.getPattern());
        DictNamingRule saved = namingRepo.save(r);
        audit.record("DICT_NAMING_CREATE", saved.getName(), saved.getPattern());
        return saved;
    }

    @PutMapping("/naming-rules/{id}")
    public ResponseEntity<DictNamingRule> updateNaming(@PathVariable Long id,
                                                       @RequestBody DictNamingRule r) {
        return namingRepo.findById(id).map(cur -> {
            validateRegex(r.getPattern());
            cur.setName(r.getName());
            cur.setTarget(r.getTarget());
            cur.setPattern(r.getPattern());
            cur.setDescription(r.getDescription());
            cur.setEnabled(r.getEnabled() == null || r.getEnabled());
            DictNamingRule saved = namingRepo.save(cur);
            audit.record("DICT_NAMING_UPDATE", saved.getName(), saved.getPattern());
            return ResponseEntity.ok(saved);
        }).orElseGet(ResponseEntity.notFound()::build);
    }

    @DeleteMapping("/naming-rules/{id}")
    public ResponseEntity<Void> deleteNaming(@PathVariable Long id) {
        if (!namingRepo.existsById(id)) {
            return ResponseEntity.notFound().build();
        }
        namingRepo.deleteById(id);
        audit.record("DICT_NAMING_DELETE", "id=" + id, null);
        return ResponseEntity.noContent().build();
    }

    private static void validateRegex(String pattern) {
        try {
            java.util.regex.Pattern.compile(pattern);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("invalid regex: " + pattern, e);
        }
    }
}
