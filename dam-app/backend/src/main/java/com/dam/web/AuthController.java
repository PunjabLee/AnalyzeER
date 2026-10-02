package com.dam.web;

import com.dam.audit.AuditService;
import com.dam.security.JwtService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    public record LoginRequest(String username, String password) { }

    private final AuthenticationManager authManager;
    private final JwtService jwtService;
    private final AuditService audit;

    public AuthController(AuthenticationManager authManager, JwtService jwtService,
                          AuditService audit) {
        this.authManager = authManager;
        this.jwtService = jwtService;
        this.audit = audit;
    }

    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> login(@RequestBody LoginRequest req) {
        try {
            var auth = authManager.authenticate(
                    new UsernamePasswordAuthenticationToken(req.username(), req.password()));
            List<String> roles = auth.getAuthorities().stream()
                    .map(GrantedAuthority::getAuthority)
                    .map(a -> a.replace("ROLE_", ""))
                    .toList();
            String token = jwtService.issue(req.username(), roles);
            audit.record(req.username(), "LOGIN", "auth", null);
            return ResponseEntity.ok(Map.of("token", token, "roles", roles));
        } catch (Exception e) {
            audit.record(req.username(), "LOGIN_FAILED", "auth", e.getMessage());
            return ResponseEntity.status(401).body(Map.of("error", "bad credentials"));
        }
    }
}
