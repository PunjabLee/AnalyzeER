package com.dam.config;

import com.dam.domain.SysUser;
import com.dam.repository.SysUserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * M1 seed users for Owner/Steward references and basic RBAC (PLAN M7.1).
 *
 * <p>Since D5 (2026-10-03) this table is the authoritative identity source (see
 * {@link com.dam.security.DbUserDetailsService}), so each row now carries a BCrypt-hashed
 * password derived from {@code dam.security.poc-password}. An existing row that predates
 * the password column is backfilled on startup (MySQL upgrade path).
 */
@Configuration
public class UserSeed {

    @Bean
    ApplicationRunner seedUsers(SysUserRepository repo, PasswordEncoder encoder,
                               @Value("${dam.security.poc-password:change-me-POC}") String pocPwd) {
        return args -> {
            String hash = encoder.encode(pocPwd);
            seed(repo, "admin", "平台管理员", "ADMIN", hash);
            seed(repo, "steward", "数据管家", "STEWARD", hash);
            seed(repo, "viewer", "只读用户", "READONLY", hash);
        };
    }

    private static void seed(SysUserRepository repo, String username, String display, String role, String hash) {
        SysUser u = repo.findByUsername(username).orElse(null);
        if (u == null) {
            u = new SysUser();
            u.setUsername(username);
            u.setDisplayName(display);
            u.setRole(role);
            u.setPasswordHash(hash);
            repo.save(u);
        } else if (u.getPasswordHash() == null || u.getPasswordHash().isBlank()) {
            u.setPasswordHash(hash);
            repo.save(u);
        }
    }
}
