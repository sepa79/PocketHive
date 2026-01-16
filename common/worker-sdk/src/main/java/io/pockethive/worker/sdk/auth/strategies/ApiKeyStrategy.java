package io.pockethive.worker.sdk.auth.strategies;

import io.pockethive.worker.sdk.api.WorkItem;
import io.pockethive.worker.sdk.auth.AuthConfig;
import io.pockethive.worker.sdk.auth.AuthStrategy;
import io.pockethive.worker.sdk.auth.TokenInfo;

import java.util.Map;

/**
 * API key authorization strategy.
 */
public class ApiKeyStrategy implements AuthStrategy {
    
    @Override
    public String getType() {
        return "api-key";
    }
    
    @Override
    public Map<String, String> generateHeaders(AuthConfig config, TokenInfo token, WorkItem item) {
        String key = config.properties().get("key");
        String keyName = config.properties().getOrDefault("keyName", "X-API-Key");
        String addTo = config.properties().getOrDefault("addTo", "header");
        
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("API key is required");
        }
        
        if ("header".equalsIgnoreCase(addTo)) {
            return Map.of(keyName, key);
        }
        
        // Query parameter handling would be done by caller
        return Map.of("X-API-Key-Query", keyName + "=" + key);
    }

    @Override
    public boolean requiresRefresh(TokenInfo token, AuthConfig config) {
        return false;
    }
}
