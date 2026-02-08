package io.pockethive.worker.sdk.auth;

import io.pockethive.worker.sdk.api.WorkItem;
import io.pockethive.worker.sdk.api.WorkerContext;
import io.pockethive.worker.sdk.templating.AuthTokenHolder;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Main entry point for generating authorization headers.
 */
public class AuthHeaderGenerator {
    
    private final InMemoryTokenStore tokenStore;
    private final Map<String, AuthStrategy> strategies;
    private final AuthConfigRegistry authConfigRegistry;
    private final Map<String, Lock> tokenLocks = new ConcurrentHashMap<>();
    
    public AuthHeaderGenerator(InMemoryTokenStore tokenStore, 
                              Map<String, AuthStrategy> strategies,
                              AuthConfigRegistry authConfigRegistry) {
        this.tokenStore = tokenStore;
        this.strategies = strategies;
        this.authConfigRegistry = authConfigRegistry;
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
        
        String tokenKey = resolved.tokenKey();
        
        AuthStrategy strategy = strategies.get(resolved.type());
        if (strategy == null) {
            throw new IllegalArgumentException("Unknown auth type: " + resolved.type());
        }
        
        // Get or refresh token when strategy requires it
        TokenInfo token = tokenStore.getToken(tokenKey).orElse(null);
        if (strategy.requiresRefresh(token, resolved)) {
            if (token == null || token.isExpired()) {
                // Synchronous refresh (blocks first request only)
                token = refreshTokenSync(resolved, strategy);
                context.statusPublisher().update(status -> status.data("auth.cacheHit", false));
            } else if (token.needsRefresh()) {
                // Emergency refresh
                token = refreshTokenSync(resolved, strategy);
                context.statusPublisher().update(status -> status.data("auth.emergencyRefresh", true));
            } else {
                context.statusPublisher().update(status -> status.data("auth.cacheHit", true));
            }
        } else if (token != null) {
            context.statusPublisher().update(status -> status.data("auth.cacheHit", true));
        }
        
        // Publish metrics
        context.statusPublisher().update(status -> status
            .data("auth.tokenKey", tokenKey)
            .data("auth.strategy", resolved.type())
        );
        
        if (token != null && token.accessToken() != null) {
            AuthTokenHolder.setToken(tokenKey, token.accessToken());
        }

        return strategy.generateHeaders(resolved, token, item);
    }
    
    /**
     * Generates authorization headers by looking up tokenKey from work item header or registry.
     */
    public Map<String, String> generateFromWorkItem(WorkerContext context, WorkItem item) {
        // Check for x-ph-auth-token-key header
        Object tokenKeyObj = item.headers().get("x-ph-auth-token-key");
        if (tokenKeyObj == null) {
            return Map.of();
        }
        
        String tokenKey = tokenKeyObj.toString().trim();
        if (tokenKey.isEmpty()) {
            return Map.of();
        }
        
        AuthConfig authConfig = authConfigRegistry.get(tokenKey);
        if (authConfig == null) {
            context.logger().debug("No auth config found for tokenKey: {}", tokenKey);
            return Map.of();
        }
        
        return generate(context, authConfig, item);
    }
    
    private TokenInfo refreshTokenSync(AuthConfig config, AuthStrategy strategy) {
        String tokenKey = config.tokenKey();
        Lock lock = tokenLocks.computeIfAbsent(tokenKey, k -> new ReentrantLock());
        lock.lock();
        try {
            // Double-check: another thread may have refreshed while we waited
            TokenInfo existing = tokenStore.getToken(tokenKey).orElse(null);
            if (existing != null && !existing.isExpired() && !existing.needsRefresh()) {
                AuthTokenHolder.setToken(tokenKey, existing.accessToken());
                return existing;
            }
            
            TokenInfo token = strategy.refresh(config);
            tokenStore.storeToken(tokenKey, token);
            AuthTokenHolder.setToken(tokenKey, token.accessToken());
            return token;
        } finally {
            lock.unlock();
        }
    }
}
