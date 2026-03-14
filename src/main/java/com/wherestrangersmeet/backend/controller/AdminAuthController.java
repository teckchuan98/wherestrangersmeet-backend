package com.wherestrangersmeet.backend.controller;

import com.wherestrangersmeet.backend.service.AdminAuthService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/admin/auth")
public class AdminAuthController {

    private final AdminAuthService adminAuthService;

    public AdminAuthController(AdminAuthService adminAuthService) {
        this.adminAuthService = adminAuthService;
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody Map<String, String> payload) {
        String username = payload.getOrDefault("username", "");
        String password = payload.getOrDefault("password", "");

        try {
            AdminAuthService.LoginResult result = adminAuthService.login(username, password);
            return ResponseEntity.ok(Map.of(
                    "token", result.token(),
                    "username", result.username(),
                    "expiresAt", result.expiresAt().toString()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(401).body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/logout")
    public ResponseEntity<?> logout(@RequestHeader(value = "Authorization", required = false) String authorization) {
        if (authorization != null && authorization.startsWith("Bearer ")) {
            adminAuthService.logout(authorization.substring(7).trim());
        }
        return ResponseEntity.ok(Map.of("loggedOut", true));
    }
}
