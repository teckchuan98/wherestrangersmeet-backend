package com.wherestrangersmeet.backend.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class AdminAuthService {

    private final String configuredUsername;
    private final String configuredPassword;
    private final long tokenTtlSeconds;
    private final Map<String, AdminSession> sessions = new ConcurrentHashMap<>();

    public AdminAuthService(
            @Value("${app.admin.username:admin}") String configuredUsername,
            @Value("${app.admin.password:change-me}") String configuredPassword,
            @Value("${app.admin.token-ttl-seconds:43200}") long tokenTtlSeconds) {
        this.configuredUsername = configuredUsername;
        this.configuredPassword = configuredPassword;
        this.tokenTtlSeconds = tokenTtlSeconds;
    }

    public LoginResult login(String username, String password) {
        if (!configuredUsername.equals(username) || !configuredPassword.equals(password)) {
            throw new IllegalArgumentException("Invalid admin credentials");
        }

        String token = UUID.randomUUID().toString();
        Instant expiresAt = Instant.now().plusSeconds(tokenTtlSeconds);
        sessions.put(token, new AdminSession(configuredUsername, expiresAt));
        return new LoginResult(token, configuredUsername, expiresAt);
    }

    public boolean isValidToken(String token) {
        if (token == null || token.isBlank()) {
            return false;
        }

        AdminSession session = sessions.get(token);
        if (session == null) {
            return false;
        }

        if (session.expiresAt().isBefore(Instant.now())) {
            sessions.remove(token);
            return false;
        }

        return true;
    }

    public String getUsername(String token) {
        AdminSession session = sessions.get(token);
        return session != null ? session.username() : null;
    }

    public void logout(String token) {
        if (token != null && !token.isBlank()) {
            sessions.remove(token);
        }
    }

    public record LoginResult(String token, String username, Instant expiresAt) {
    }

    private record AdminSession(String username, Instant expiresAt) {
    }
}
