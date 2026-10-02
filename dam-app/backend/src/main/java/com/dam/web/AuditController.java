package com.dam.web;

import com.dam.domain.SysAuditLog;
import com.dam.repository.SysAuditLogRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Audit trail query (PLAN M7.2). Restricted to ROLE_ADMIN in SecurityConfig.
 */
@RestController
@RequestMapping("/api/audit")
public class AuditController {

    private final SysAuditLogRepository repo;

    public AuditController(SysAuditLogRepository repo) {
        this.repo = repo;
    }

    @GetMapping
    public Page<SysAuditLog> list(@RequestParam(required = false) String username,
                                  @RequestParam(required = false) String action,
                                  @RequestParam(defaultValue = "0") int page,
                                  @RequestParam(defaultValue = "50") int size) {
        PageRequest pr = PageRequest.of(page, Math.min(size, 500),
                Sort.by(Sort.Direction.DESC, "atTs"));
        boolean hasU = username != null && !username.isBlank();
        boolean hasA = action != null && !action.isBlank();
        if (hasU && hasA) return repo.findByUsernameContainingIgnoreCaseAndActionContainingIgnoreCase(username, action, pr);
        if (hasU) return repo.findByUsernameContainingIgnoreCase(username, pr);
        if (hasA) return repo.findByActionContainingIgnoreCase(action, pr);
        return repo.findAll(pr);
    }
}
