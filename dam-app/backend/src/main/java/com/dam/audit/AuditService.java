package com.dam.audit;

import com.dam.domain.SysAuditLog;
import com.dam.repository.SysAuditLogRepository;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * Writes the operation audit trail (PLAN M7.2). Recording must never break the
 * main flow, so failures are swallowed with a warn-level log.
 */
@Service
public class AuditService {

    private final SysAuditLogRepository repo;

    public AuditService(SysAuditLogRepository repo) {
        this.repo = repo;
    }

    /** Explicit actor version (used at login, before the SecurityContext is set). */
    public void record(String username, String action, String target, String detail) {
        try {
            SysAuditLog log = new SysAuditLog();
            log.setUsername(username == null ? "anonymous" : username);
            log.setAction(action);
            log.setTarget(truncate(target, 200));
            log.setDetail(truncate(detail, 1000));
            log.setAtTs(Instant.now());
            repo.save(log);
        } catch (RuntimeException e) {
            System.err.println("audit write failed: " + e.getMessage());
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
