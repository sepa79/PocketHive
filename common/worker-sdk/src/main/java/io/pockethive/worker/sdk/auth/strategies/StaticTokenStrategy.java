package io.pockethive.worker.sdk.auth.strategies;

import io.pockethive.worker.sdk.api.WorkItem;
import io.pockethive.worker.sdk.auth.AuthConfig;
import io.pockethive.worker.sdk.auth.AuthStrategy;
import io.pockethive.worker.sdk.auth.TokenInfo;

import java.util.Map;

/**
 * Static token strategy for pre-shared tokens in TCP protocols.
 */
public class StaticTokenStrategy implements AuthStrategy {
    
    @Override
    public String getType() {
        return "static-token";
    }
    
    @Override
    public Map<String, String> generateHeaders(AuthConfig config, TokenInfo token, WorkItem item) {
        String tokenValue = config.properties().get("token");
        String headerName = config.properties().getOrDefault("headerName", "x-ph-auth-token");
        
        if (tokenValue == null) {
            throw new IllegalArgumentException("token is required for static token auth");
        }
        
        return Map.of(headerName, tokenValue);
    }
}
