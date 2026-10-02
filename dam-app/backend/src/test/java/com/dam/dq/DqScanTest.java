package com.dam.dq;

import com.dam.repository.DqIssueRepository;
import com.dam.repository.DqRuleRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Structure-class dq scan assertions (PLAN §6.1 exit numbers of the 05 issue list):
 * no-PK=11 (00-总览 8.3), charset drift=0 (DDL is uniformly utf8mb4 at table level),
 * camelCase columns inside A-grade scope=7 (05 §C类命名漂移).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class DqScanTest {

    @Autowired
    DqScanService scanService;
    @Autowired
    DqRuleRepository ruleRepo;
    @Autowired
    DqIssueRepository issueRepo;

    @Test
    void rulesAndDictAreSeeded() {
        assertEquals(4, ruleRepo.count(), "3 structure + 1 disabled relationship rule");
        assertTrue(ruleRepo.findByCode("R-REL-001").orElseThrow().getEnabled() == false,
                "relationship rules stay disabled until lineage (review #6)");
    }

    @Test
    void scanHitsMatchTheModelReviewNumbers() {
        DqScanService.ScanReport report = scanService.scan();
        Map<String, Integer> byRule = report.byRule();
        assertEquals(11, byRule.get("R-STR-001"), "11 tables without PK");
        assertEquals(0, byRule.get("R-STR-003"), "all tables already utf8mb4");
        assertEquals(7, byRule.get("R-STR-002"), "7 camelCase columns in A-grade tables");
        assertEquals(18, report.issues(), "total = 11 + 0 + 7");

        // issues are persisted and queryable for the worklist
        assertEquals(18L, issueRepo.count());
        assertTrue(issueRepo.findByStatus("open").stream()
                .anyMatch(i -> i.getColumnName() != null && i.getColumnName().equals("deductMode")),
                "jf_sales_order.deductMode flagged as naming drift");

        // re-scan is idempotent (full replace, not append)
        assertEquals(18, scanService.scan().issues());
        assertEquals(18L, issueRepo.count());
    }
}
