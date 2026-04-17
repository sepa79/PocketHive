package io.pockethive.auth.service.domain;

import java.time.Instant;
import java.util.UUID;

public record AuthSession(String accessToken, UUID userId, Instant expiresAt) {
    public AuthSession {
        if (accessToken == null || accessToken.isBlank()) {
            throw new IllegalArgumentException("accessToken must not be null or blank");
        }
        if (userId == null) {
            throw new IllegalArgumentException("userId must not be null");
        }
        if (expiresAt == null) {
            throw new IllegalArgumentException("expiresAt must not be null");
        }
        accessToken = accessToken.trim();
    }
}
