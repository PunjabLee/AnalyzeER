package com.dam.repository;

import com.dam.domain.SysAuditLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SysAuditLogRepository extends JpaRepository<SysAuditLog, Long> {

    Page<SysAuditLog> findByUsernameContainingIgnoreCase(String username, Pageable pageable);

    Page<SysAuditLog> findByActionContainingIgnoreCase(String action, Pageable pageable);

    Page<SysAuditLog> findByUsernameContainingIgnoreCaseAndActionContainingIgnoreCase(
            String username, String action, Pageable pageable);
}
