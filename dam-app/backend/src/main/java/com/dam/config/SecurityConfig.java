package com.dam.config;

import com.dam.security.JwtAuthFilter;
import org.springframework.beans.factory.annotation.Value;
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
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * M1 basic RBAC (PLAN M7.1): catalog reads are open, metadata writes require
 * ADMIN/STEWARD, ingestion requires ADMIN. JWT is validated by {@link JwtAuthFilter}.
 *
 * <p>POC users are in-memory with configurable default passwords — [待确认] the real
 * identity source (LDAP/OIDC?) before production.
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
                        .requestMatchers("/api/audit/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.GET, "/api/**").permitAll()
                        .requestMatchers("/api/ingest/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/**").hasAnyRole("ADMIN", "STEWARD")
                        .requestMatchers(HttpMethod.PUT, "/api/**").hasAnyRole("ADMIN", "STEWARD")
                        .requestMatchers(HttpMethod.PATCH, "/api/**").hasAnyRole("ADMIN", "STEWARD")
                        .requestMatchers(HttpMethod.DELETE, "/api/**").hasRole("ADMIN")
                        .anyRequest().permitAll())
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    UserDetailsService users(@Value("${dam.security.poc-password:change-me-POC}") String pwd) {
        return new InMemoryUserDetailsManager(
                User.withUsername("admin").password("{noop}" + pwd).roles("ADMIN").build(),
                User.withUsername("steward").password("{noop}" + pwd).roles("STEWARD").build(),
                User.withUsername("viewer").password("{noop}" + pwd).roles("READONLY").build());
    }

    @Bean
    AuthenticationManager authManager(UserDetailsService users) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider();
        provider.setUserDetailsService(users);
        return new ProviderManager(provider);
    }
}
