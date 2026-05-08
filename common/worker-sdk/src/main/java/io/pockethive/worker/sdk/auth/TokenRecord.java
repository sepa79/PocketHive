package io.pockethive.worker.sdk.auth;

import java.time.Instant;

public record TokenRecord(
    String tokenKey,
    String fingerprint,
    String accessToken,
    String tokenType,
    Instant expiresAt,
    Instant refreshAt
) {
    public boolean expired(Instant now) {
        return expiresAt == null || !expiresAt.isAfter(now);
    }

    public boolean needsRefresh(Instant now) {
        return refreshAt == null || !refreshAt.isAfter(now);
    }
}
