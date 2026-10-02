package com.dam.security;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JwtServiceTest {

    private final JwtService svc =
            new JwtService("unit-test-secret-0123456789abcdef-32bytes+", 1);

    @Test
    void issueAndVerifyRoundTrip() {
        String token = svc.issue("admin", List.of("ADMIN"));
        Map<String, Object> claims = svc.verify(token);
        assertEquals("admin", claims.get("sub"));
        assertEquals(List.of("ADMIN"), claims.get("roles"));
    }

    @Test
    void tamperedTokenIsRejected() {
        String token = svc.issue("admin", List.of("ADMIN"));
        assertNull(svc.verify(token + "x"));
        assertNull(svc.verify("garbage"));
    }

    @Test
    void shortSecretIsRejected() {
        assertThrows(IllegalStateException.class, () -> new JwtService("too-short", 1));
    }
}
