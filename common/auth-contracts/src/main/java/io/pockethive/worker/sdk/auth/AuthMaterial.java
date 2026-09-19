package io.pockethive.worker.sdk.auth;

import java.time.Instant;

/**
 * Responsibility: define the AuthMaterial contract.
 * Must not: configure transport clients or own adapter lifecycle.
 * Contract: RESP-AUTH-VALUES — docs/architecture/runtime-responsibilities.md#resp-auth-values.
 */
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
