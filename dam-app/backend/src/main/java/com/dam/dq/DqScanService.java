package com.dam.dq;

import com.dam.audit.AuditService;
import com.dam.domain.DqIssue;
import com.dam.domain.DqRule;
import com.dam.domain.MetaAsset;
import com.dam.domain.MetaColumn;
import com.dam.repository.DqIssueRepository;
import com.dam.repository.DqRuleRepository;
import com.dam.repository.MetaAssetRepository;
import com.dam.repository.MetaColumnRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Structure-class dq scanner (PLAN §6.1, review #6: runs already in M1).
 * A scan is a full replace of previous issues (idempotent snapshot per run);
 * relationship-class checkers are placeholders until lineage lands.
 */
@Service
public class DqScanService {

    public record ScanReport(int issues, Map<String, Integer> byRule, Instant scannedAt) { }

    private final DqRuleRepository ruleRepo;
    private final DqIssueRepository issueRepo;
    private final MetaAssetRepository assetRepo;
    private final MetaColumnRepository columnRepo;
    private final AuditService audit;

    public DqScanService(DqRuleRepository ruleRepo, DqIssueRepository issueRepo,
                         MetaAssetRepository assetRepo, MetaColumnRepository columnRepo,
                         AuditService audit) {
        this.ruleRepo = ruleRepo;
        this.issueRepo = issueRepo;
        this.assetRepo = assetRepo;
        this.columnRepo = columnRepo;
        this.audit = audit;
    }

    @Transactional
    public ScanReport scan() {
        Instant now = Instant.now();
        issueRepo.deleteAllInBatch();

        List<MetaAsset> assets = assetRepo.findAllByOrderByNameAsc();

        List<DqIssue> found = new ArrayList<>();
        Map<String, Integer> byRule = new LinkedHashMap<>();
        for (DqRule rule : ruleRepo.findByEnabledTrue()) {
            int before = found.size();
            switch (rule.getChecker()) {
                case "NO_PK" -> found.addAll(noPk(rule, assets, now));
                case "CHARSET" -> found.addAll(charset(rule, assets, now));
                case "COLUMN_NAMING" -> found.addAll(columnNaming(rule, assets, now));
                case "TABLE_NAMING" -> found.addAll(tableNaming(rule, assets, now));
                default -> { /* relationship placeholders: nothing to run yet */ }
            }
            byRule.put(rule.getCode(), found.size() - before);
        }
        issueRepo.saveAll(found);
        audit.record("DQ_SCAN", "dq_issue", "issues=" + found.size() + " byRule=" + byRule);
        return new ScanReport(found.size(), byRule, now);
    }

    // ---- built-in structure checkers -------------------------------------------------------

    /** 00-总览 8.3: 11 tables without primary key (including formal business tables). */
    private List<DqIssue> noPk(DqRule rule, List<MetaAsset> assets, Instant now) {
        return assets.stream()
                .filter(a -> !Boolean.TRUE.equals(a.getHasPk()))
                .map(a -> issue(rule, a, null, "表无主键（has_pk=false）", now))
                .toList();
    }

    private List<DqIssue> charset(DqRule rule, List<MetaAsset> assets, Instant now) {
        String expect = rule.getParam() == null ? "utf8mb4" : rule.getParam();
        return assets.stream()
                .filter(a -> a.getCharset() != null && !a.getCharset().equalsIgnoreCase(expect))
                .map(a -> issue(rule, a, null, "表字符集 " + a.getCharset() + " != " + expect, now))
                .toList();
    }

    private List<DqIssue> tableNaming(DqRule rule, List<MetaAsset> assets, Instant now) {
        Pattern p = Pattern.compile(rule.getParam());
        return scoped(assets, rule).stream()
                .filter(a -> !p.matcher(a.getName()).matches())
                .map(a -> issue(rule, a, null, "表名不匹配 " + rule.getParam(), now))
                .toList();
    }

    /** per-column snake_case check; scope grading keeps framework-table noise out (M1 default A) */
    private List<DqIssue> columnNaming(DqRule rule, List<MetaAsset> assets, Instant now) {
        Pattern p = Pattern.compile(rule.getParam());
        List<DqIssue> out = new ArrayList<>();
        for (MetaAsset a : scoped(assets, rule)) {
            for (MetaColumn c : columnRepo.findByAssetIdOrderByOrdinalAsc(a.getId())) {
                if (c.getName() != null && !p.matcher(c.getName()).matches()) {
                    out.add(issue(rule, a, c.getName(),
                            "列名 " + c.getName() + " 不匹配 " + rule.getParam(), now));
                }
            }
        }
        if (out.isEmpty() && rule.getScopeGrading() != null) {
            // sanity: confirm the scoped slice actually exists, so 0 hits never means "wrong scope"
            boolean slicePresent = assets.stream()
                    .anyMatch(x -> rule.getScopeGrading().equalsIgnoreCase(x.getGrading()));
            if (!slicePresent) {
                throw new IllegalStateException("dq rule " + rule.getCode()
                        + " scope grading " + rule.getScopeGrading() + " matches no assets");
            }
        }
        return out;
    }

    private static List<MetaAsset> scoped(List<MetaAsset> assets, DqRule rule) {
        if (rule.getScopeGrading() == null || rule.getScopeGrading().isBlank()) {
            return assets;
        }
        return assets.stream()
                .filter(a -> rule.getScopeGrading().equalsIgnoreCase(a.getGrading()))
                .toList();
    }

    private static DqIssue issue(DqRule rule, MetaAsset a, String column, String detail, Instant now) {
        DqIssue i = new DqIssue();
        i.setRuleCode(rule.getCode());
        i.setAssetUrn(a.getAssetUrn());
        i.setColumnName(column);
        i.setDetail(truncate(detail, 500));
        i.setScannedAt(now);
        i.setStatus("open");
        return i;
    }

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }

    /** open issues grouped by rule for the quality dashboard header */
    public Map<String, Long> openSummary() {
        return issueRepo.findByStatus("open").stream()
                .collect(Collectors.groupingBy(DqIssue::getRuleCode,
                        LinkedHashMap::new, Collectors.counting()));
    }
}
