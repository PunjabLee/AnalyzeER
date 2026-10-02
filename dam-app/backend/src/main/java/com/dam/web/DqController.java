package com.dam.web;

import com.dam.domain.DqIssue;
import com.dam.domain.DqRule;
import com.dam.dq.DqScanService;
import com.dam.repository.DqIssueRepository;
import com.dam.repository.DqRuleRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Quality rules &amp; issues (PLAN §6.1): list rules, trigger a structure scan,
 * browse the hit list. POST /scan requires ADMIN/STEWARD via SecurityConfig.
 */
@RestController
@RequestMapping("/api/dq")
public class DqController {

    private final DqRuleRepository ruleRepo;
    private final DqIssueRepository issueRepo;
    private final DqScanService scanService;

    public DqController(DqRuleRepository ruleRepo, DqIssueRepository issueRepo,
                        DqScanService scanService) {
        this.ruleRepo = ruleRepo;
        this.issueRepo = issueRepo;
        this.scanService = scanService;
    }

    @GetMapping("/rules")
    public List<DqRule> rules() {
        return ruleRepo.findAll();
    }

    @PostMapping("/scan")
    public DqScanService.ScanReport scan() {
        return scanService.scan();
    }

    /** open-issue counts per rule (dashboard header) */
    @GetMapping("/issues/summary")
    public Map<String, Long> summary() {
        return scanService.openSummary();
    }

    @GetMapping("/issues")
    public Page<DqIssue> issues(@RequestParam(required = false) String ruleCode,
                                @RequestParam(defaultValue = "open") String status,
                                @RequestParam(defaultValue = "0") int page,
                                @RequestParam(defaultValue = "100") int size) {
        PageRequest pr = PageRequest.of(page, Math.min(size, 500),
                Sort.by(Sort.Direction.ASC, "ruleCode", "assetUrn"));
        if (ruleCode != null && !ruleCode.isBlank()) {
            return issueRepo.findByRuleCodeAndStatusContaining(ruleCode, status, pr);
        }
        return issueRepo.findByStatus(status, pr);
    }
}
