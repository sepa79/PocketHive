package io.pockethive.worker.sdk.auth;

import java.time.Instant;
import java.util.Map;

/**
 * Immutable token metadata stored in Redis.
 */
public record TokenInfo(
    String accessToken,
    String tokenType,
    long expiresAt,
    long refreshAt,
    String strategy,
    Map<String, String> refreshConfig,
    Map<String, Object> metadata
) {
    
    public boolean isExpired() {
        return Instant.now().getEpochSecond() >= expiresAt;
    }
    
    public boolean needsRefresh() {
        return Instant.now().getEpochSecond() >= refreshAt;
    }
}
