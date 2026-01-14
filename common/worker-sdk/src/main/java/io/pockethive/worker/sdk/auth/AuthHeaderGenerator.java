package io.pockethive.worker.sdk.auth;

import io.pockethive.worker.sdk.api.WorkItem;
import io.pockethive.worker.sdk.api.WorkerContext;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Main entry point for generating authorization headers.
 */
public class AuthHeaderGenerator {
    
    private final RedisTokenStore tokenStore;
    private final Map<String, AuthStrategy> strategies;
    
    public AuthHeaderGenerator(RedisTokenStore tokenStore, Map<String, AuthStrategy> strategies) {
        this.tokenStore = tokenStore;
        this.strategies = strategies;
    }
    
    /**
     * Generates authorization headers for the given config and work item.
     */
    public Map<String, String> generate(WorkerContext context, AuthConfig authConfig, WorkItem item) {
        if (authConfig == null || "none".equalsIgnoreCase(authConfig.type())) {
            return Map.of();
        }
        
        // Resolve dynamic variables (headers)
        AuthConfig resolved = authConfig.resolveHeaders(item);
        
        String swarmInstanceId = context.info().swarmInstanceId();
        String tokenKey = resolved.tokenKey();
        String storeKey = "phauth:" + swarmInstanceId + ":token:" + tokenKey;
        
        AuthStrategy strategy = strategies.get(resolved.type());
        if (strategy == null) {
            throw new IllegalArgumentException("Unknown auth type: " + resolved.type());
        }
        
        // Get or refresh token
        Optional<TokenInfo> token = tokenStore.getToken(storeKey);
        
        if (token.isEmpty() || token.get().isExpired()) {
            // Synchronous refresh (blocks first request only)
            token = Optional.of(refreshTokenSync(swarmInstanceId, resolved, strategy));
            context.statusPublisher().update(status -> status.data("auth.cacheHit", false));
        } else if (token.get().needsRefresh()) {
            // Emergency refresh
            token = Optional.of(refreshTokenSync(swarmInstanceId, resolved, strategy));
            context.statusPublisher().update(status -> status.data("auth.emergencyRefresh", true));
        } else {
            context.statusPublisher().update(status -> status.data("auth.cacheHit", true));
        }
        
        // Publish metrics
        context.statusPublisher().update(status -> status
            .data("auth.tokenKey", tokenKey)
            .data("auth.strategy", resolved.type())
        );
        
        return strategy.generateHeaders(resolved, token.orElse(null), item);
    }
    
    private TokenInfo refreshTokenSync(String swarmInstanceId, AuthConfig config, AuthStrategy strategy) {
        TokenInfo token = strategy.refresh(config);
        String storeKey = "phauth:" + swarmInstanceId + ":token:" + config.tokenKey();
        tokenStore.storeToken(storeKey, token);
        tokenStore.scheduleRefresh(swarmInstanceId, config.tokenKey(), token.refreshAt());
        return token;
    }
}
