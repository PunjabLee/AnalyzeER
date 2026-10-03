package com.dam.security;

import com.dam.domain.SysUser;
import com.dam.repository.SysUserRepository;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

/**
 * Authoritative identity source for the POC (D5 decision 2026-10-03, PLAN R6): the
 * dam_meta-owned {@code sys_user} table replaces the previous in-memory users so that
 * Owner/Steward governance references (meta_asset.owner_id -> sys_user.id) resolve against
 * the same identity the login flow authenticates.
 *
 * <p>The stored password is a BCrypt hash validated by the {@code PasswordEncoder} bean in
 * {@code SecurityConfig}. A row without a hash cannot authenticate (fail-closed).
 *
 * <p>Production IAM (SSO/LDAP/OAuth) remains a pending decision; when it lands it replaces
 * this bean only, leaving login/JWT/RBAC wiring untouched.
 */
@Service
public class DbUserDetailsService implements UserDetailsService {

    private final SysUserRepository userRepo;

    public DbUserDetailsService(SysUserRepository userRepo) {
        this.userRepo = userRepo;
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        SysUser u = userRepo.findByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException("user not found: " + username));
        String hash = u.getPasswordHash();
        if (hash == null || hash.isBlank()) {
            // fail closed: an identity row without a credential can never authenticate
            throw new UsernameNotFoundException("user has no credential: " + username);
        }
        return User.withUsername(u.getUsername())
                .password(hash)
                .roles(u.getRole())
                .build();
    }
}
