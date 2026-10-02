package com.dam.config;

import com.dam.domain.SysUser;
import com.dam.repository.SysUserRepository;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * M1 seed users for Owner/Steward references and basic RBAC (PLAN M7.1).
 * Passwords live in application.yml via Spring Security users (M1e); this table only
 * stores the identity rows the governance attributes point at.
 */
@Configuration
public class UserSeed {

    @Bean
    ApplicationRunner seedUsers(SysUserRepository repo) {
        return args -> {
            seed(repo, "admin", "平台管理员", "ADMIN");
            seed(repo, "steward", "数据管家", "STEWARD");
            seed(repo, "viewer", "只读用户", "READONLY");
        };
    }

    private static void seed(SysUserRepository repo, String username, String display, String role) {
        if (repo.findByUsername(username).isEmpty()) {
            SysUser u = new SysUser();
            u.setUsername(username);
            u.setDisplayName(display);
            u.setRole(role);
            repo.save(u);
        }
    }
}
