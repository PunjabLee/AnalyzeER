package com.dam.audit;

import com.dam.domain.SysAuditLog;
import com.dam.repository.SysAuditLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Writes the operation audit trail (PLAN M7.2). Recording must never break the
 * main flow, so failures are swallowed with a warn-level log.
 */
@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    private final SysAuditLogRepository repo;

    public AuditService(SysAuditLogRepository repo) {
        this.repo = repo;
    }

    /**
     * Explicit actor version (used at login, before the SecurityContext is set).
     *
     * <p>Runs in its OWN transaction (REQUIRES_NEW): when called from within a caller
     * transaction (a governance PATCH or a dq scan), a failing audit insert must NOT mark the
     * caller's transaction rollback-only — otherwise swallowing it here would only resurface
     * later as an UnexpectedRollbackException at commit, defeating "never break the main flow".
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(String username, String action, String target, String detail) {
        try {
            SysAuditLog row = new SysAuditLog();
            row.setUsername(username == null ? "anonymous" : username);
            row.setAction(action);
            row.setTarget(truncate(target, 200));
            row.setDetail(truncate(detail, 1000));
            row.setAtTs(Instant.now());
            repo.save(row);
        } catch (RuntimeException e) {
            log.warn("audit write failed (action={}, user={}): {}", action, username, e.toString());
        }
    }

    /** Actor taken from the current SecurityContext (authenticated requests). */
    public void record(String action, String target, String detail) {
        record(currentUsername(), action, target, detail);
    }

    public static String currentUsername() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        return a == null ? "anonymous" : a.getName();
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}
