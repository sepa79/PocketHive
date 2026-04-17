package io.pockethive.auth.service.domain;

import java.time.Instant;
import java.util.UUID;

public record AuthSession(String accessToken, AuthSessionPrincipalKind principalKind, UUID principalId, Instant expiresAt) {
    public AuthSession {
        if (accessToken == null || accessToken.isBlank()) {
            throw new IllegalArgumentException("accessToken must not be null or blank");
        }
        if (principalKind == null) {
            throw new IllegalArgumentException("principalKind must not be null");
        }
        if (principalId == null) {
            throw new IllegalArgumentException("principalId must not be null");
        }
        if (expiresAt == null) {
            throw new IllegalArgumentException("expiresAt must not be null");
        }
        accessToken = accessToken.trim();
    }
}
