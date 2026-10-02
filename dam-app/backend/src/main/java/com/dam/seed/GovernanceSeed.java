package com.dam.seed;

import com.dam.domain.DictCodeValue;
import com.dam.domain.DictNamingRule;
import com.dam.domain.DictStandardField;
import com.dam.domain.DqRule;
import com.dam.repository.DictCodeValueRepository;
import com.dam.repository.DictNamingRuleRepository;
import com.dam.repository.DictStandardFieldRepository;
import com.dam.repository.DqRuleRepository;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * M1 initial dictionary + rule data (PLAN §M2, §6.1): the 30-item review list of
 * er-model/05 is introduced "初版" here as structure-class dq rules; relationship
 * rules are seeded disabled until lineage lands (review #6).
 */
@Configuration
public class GovernanceSeed {

    @Bean
    ApplicationRunner seedGovernanceBasics(DqRuleRepository rules, DictNamingRuleRepository naming,
                                           DictStandardFieldRepository fields, DictCodeValueRepository codes) {
        return args -> {
            seedRule(rules, "R-STR-001", "表必须有主键", "structure", "NO_PK", null, null, "high", true);
            seedRule(rules, "R-STR-002", "列名必须为 snake_case", "structure", "COLUMN_NAMING",
                    "^[a-z][a-z0-9_]*$", "A", "medium", true);
            seedRule(rules, "R-STR-003", "表字符集必须为 utf8mb4", "structure", "CHARSET",
                    "utf8mb4", null, "low", true);
            // relationship-class: needs lineage/column stats, kept disabled in M1
            seedRule(rules, "R-REL-001", "同名列异指向检查(05 B-4)", "relationship", "NONE",
                    null, null, "high", false);

            seedNaming(naming, "table-snake", "table", "^[a-z][a-z0-9_]*$", "表名全小写下划线");
            seedNaming(naming, "column-snake", "column", "^[a-z][a-z0-9_]*$", "列名全小写下划线(对治 05 C类命名漂移)");

            seedField(fields, "id", "bigint", null, "主键，自增");
            seedField(fields, "create_time", "datetime", null, "创建时间");
            seedField(fields, "update_time", "datetime", null, "更新时间");
            seedField(fields, "is_deleted", "tinyint", null, "逻辑删除标志 0/1");
            seedField(fields, "remark", "varchar", 500, "备注");

            seedCode(codes, "confirm_status", "待确认", "推断关系未经人工确认（默认态）", 1);
            seedCode(codes, "confirm_status", "已确认", "管家确认推断成立", 2);
            seedCode(codes, "confirm_status", "已驳回", "管家确认推断不成立", 3);
            seedCode(codes, "certification_status", "认证", "资产信息可信", 1);
            seedCode(codes, "certification_status", "待审", "进入认证评审流程", 2);
            seedCode(codes, "certification_status", "未认证", "默认态", 3);
            seedCode(codes, "sensitivity_level", "PII", "含个人敏感信息", 1);
            seedCode(codes, "sensitivity_level", "机密", "商业机密", 2);
            seedCode(codes, "sensitivity_level", "内部", "仅限内部使用", 3);
            seedCode(codes, "sensitivity_level", "公开", "可对外", 4);
        };
    }

    private static void seedRule(DqRuleRepository repo, String code, String name, String category,
                                 String checker, String param, String scopeGrading,
                                 String severity, boolean enabled) {
        if (repo.findByCode(code).isEmpty()) {
            DqRule r = new DqRule();
            r.setCode(code);
            r.setName(name);
            r.setCategory(category);
            r.setChecker(checker);
            r.setParam(param);
            r.setScopeGrading(scopeGrading);
            r.setSeverity(severity);
            r.setEnabled(enabled);
            repo.save(r);
        }
    }

    private static void seedNaming(DictNamingRuleRepository repo, String name, String target,
                                   String pattern, String desc) {
        boolean exists = repo.findAllByOrderByNameAsc().stream().anyMatch(n -> n.getName().equals(name));
        if (!exists) {
            DictNamingRule r = new DictNamingRule();
            r.setName(name);
            r.setTarget(target);
            r.setPattern(pattern);
            r.setDescription(desc);
            r.setEnabled(true);
            repo.save(r);
        }
    }

    private static void seedField(DictStandardFieldRepository repo, String fieldName, String type,
                                  Integer len, String semantic) {
        boolean exists = repo.findAllByOrderByFieldNameAsc().stream()
                .anyMatch(f -> f.getFieldName().equals(fieldName));
        if (!exists) {
            DictStandardField f = new DictStandardField();
            f.setFieldName(fieldName);
            f.setDataType(type);
            f.setLengthVal(len);
            f.setSemantic(semantic);
            f.setEnabled(true);
            repo.save(f);
        }
    }

    private static void seedCode(DictCodeValueRepository repo, String category, String value,
                                 String meaning, int ordinal) {
        boolean exists = repo.findByCategoryOrderByOrdinalAscIdAsc(category).stream()
                .anyMatch(c -> c.getCodeValue().equals(value));
        if (!exists) {
            DictCodeValue c = new DictCodeValue();
            c.setCategory(category);
            c.setCodeValue(value);
            c.setMeaning(meaning);
            c.setOrdinal(ordinal);
            repo.save(c);
        }
    }
}
