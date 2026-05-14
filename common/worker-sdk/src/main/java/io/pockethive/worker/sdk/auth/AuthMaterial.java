package io.pockethive.worker.sdk.auth;

import java.time.Instant;

public record AuthMaterial(
    String value,
    String tokenType,
    Instant expiresAt,
    Instant refreshAt
) {
    public AuthMaterial {
        value = value == null ? "" : value;
        tokenType = tokenType == null || tokenType.isBlank() ? "Bearer" : tokenType.trim();
    }

    public boolean refreshable() {
        return expiresAt != null && refreshAt != null;
    }
}
