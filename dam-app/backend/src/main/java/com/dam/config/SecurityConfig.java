package com.dam.config;

import com.dam.security.JwtAuthFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * M1 basic RBAC (PLAN M7.1) hardened per review item M-4 (2026-10-04, owner-approved 口径):
 * <b>fail-closed by default</b> — {@code anyRequest()} is {@code denyAll()}, so anything not
 * explicitly matched (future controllers, /actuator, stray paths) is refused rather than opened.
 *
 * <p>Read 口径 (POC): catalog/lineage GETs stay anonymous-readable (export URLs open straight in
 * a browser); every write keeps its role gate (ADMIN/STEWARD/ADMIN per verb+path). [待确认] when
 * production IAM lands, the {@code GET /api/**} permitAll line flips to {@code authenticated()}
 * (or a viewer role) — that single matcher is the whole change surface.
 *
 * <p>Identity comes from the dam_meta {@code sys_user} table via
 * {@link com.dam.security.DbUserDetailsService} with BCrypt-hashed credentials
 * (D5 decision 2026-10-03). [待确认] production IAM (SSO/LDAP/OAuth) — when it lands it
 * only swaps the {@link UserDetailsService} bean; login/JWT/RBAC wiring stays unchanged.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    SecurityFilterChain chain(HttpSecurity http, JwtAuthFilter jwtFilter) throws Exception {
        http.csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(reg -> reg
                        .requestMatchers("/api/auth/**").permitAll()
                        // CORS preflight never carries Authorization — must stay open
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers("/api/audit/**").hasRole("ADMIN")
                        // POC read 口径 (owner decision 2026-10-04): GET/HEAD /api/** anonymous;
                        // flip this ONE matcher to authenticated() (or a viewer role) for production
                        .requestMatchers(HttpMethod.GET, "/api/**").permitAll()
                        .requestMatchers(HttpMethod.HEAD, "/api/**").permitAll()
                        .requestMatchers("/api/ingest/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/**").hasAnyRole("ADMIN", "STEWARD")
                        .requestMatchers(HttpMethod.PUT, "/api/**").hasAnyRole("ADMIN", "STEWARD")
                        .requestMatchers(HttpMethod.PATCH, "/api/**").hasAnyRole("ADMIN", "STEWARD")
                        .requestMatchers(HttpMethod.DELETE, "/api/**").hasRole("ADMIN")
                        // M-4: fail-CLOSED, not fail-open — unmatched paths are refused outright
                        .anyRequest().denyAll())
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    AuthenticationManager authManager(UserDetailsService users, PasswordEncoder encoder) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider();
        provider.setUserDetailsService(users);
        provider.setPasswordEncoder(encoder);
        return new ProviderManager(provider);
    }
}
