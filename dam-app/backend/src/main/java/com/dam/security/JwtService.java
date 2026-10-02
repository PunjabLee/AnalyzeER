package com.dam.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * Minimal JWT issue/parse (M1 basic RBAC, PLAN M7.1). HS256 with a configured secret;
 * roles are carried as a claim and mapped to ROLE_* authorities.
 */
@Service
public class JwtService {

    private final SecretKey key;
    private final long ttlHours;

    public JwtService(@Value("${dam.security.jwt-secret}") String secret,
                      @Value("${dam.security.jwt-ttl-hours:12}") long ttlHours) {
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("dam.security.jwt-secret must be >= 32 bytes");
        }
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.ttlHours = ttlHours;
    }

    public String issue(String username, List<String> roles) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(username)
                .claim("roles", roles)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(ttlHours, ChronoUnit.HOURS)))
                .signWith(key)
                .compact();
    }

    /** returns username + roles when the token is valid; null otherwise */
    public Map<String, Object> verify(String token) {
        try {
            Claims c = Jwts.parser().verifyWith(key).build()
                    .parseSignedClaims(token).getPayload();
            @SuppressWarnings("unchecked")
            List<String> roles = c.get("roles", List.class);
            return Map.of("sub", c.getSubject(), "roles", roles == null ? List.of() : roles);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
