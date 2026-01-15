package io.pockethive.worker.sdk.auth.strategies;

import io.pockethive.worker.sdk.api.WorkItem;
import io.pockethive.worker.sdk.auth.AuthConfig;
import io.pockethive.worker.sdk.auth.AuthStrategy;
import io.pockethive.worker.sdk.auth.TokenInfo;

import java.util.Map;

/**
 * Bearer token authorization strategy.
 */
public class BearerTokenStrategy implements AuthStrategy {
    
    @Override
    public String getType() {
        return "bearer-token";
    }
    
    @Override
    public Map<String, String> generateHeaders(AuthConfig config, TokenInfo token, WorkItem item) {
        String tokenValue = config.properties().get("token");
        if (tokenValue == null || tokenValue.isBlank()) {
            throw new IllegalArgumentException("Bearer token is required");
        }
        return Map.of("Authorization", "Bearer " + tokenValue);
    }
}
