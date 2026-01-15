package io.pockethive.worker.sdk.auth;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Background scheduler for proactive token refresh.
 */
public class TokenRefreshScheduler {
    
    private static final Logger log = LoggerFactory.getLogger(TokenRefreshScheduler.class);
    
    private final InMemoryTokenStore tokenStore;
    private final Map<String, AuthStrategy> strategies;
    private final MeterRegistry meterRegistry;
    private final Map<String, Lock> tokenLocks = new ConcurrentHashMap<>();
    
    public TokenRefreshScheduler(
        InMemoryTokenStore tokenStore,
        Map<String, AuthStrategy> strategies,
        MeterRegistry meterRegistry
    ) {
        this.tokenStore = tokenStore;
        this.strategies = strategies;
        this.meterRegistry = meterRegistry;
    }
    
    @Scheduled(fixedDelay = 10000) // Every 10 seconds
    public void scanAndRefresh() {
        try {
            long now = Instant.now().getEpochSecond();
            
            for (Map.Entry<String, TokenInfo> entry : tokenStore.getAllTokens().entrySet()) {
                String tokenKey = entry.getKey();
                TokenInfo token = entry.getValue();
                
                if (token.refreshAt() <= now && !token.isExpired()) {
                    refreshToken(tokenKey, token);
                }
            }
        } catch (Exception e) {
            log.error("Token refresh scan failed", e);
        }
    }
    
    private void refreshToken(String tokenKey, TokenInfo oldToken) {
        Lock lock = tokenLocks.computeIfAbsent(tokenKey, k -> new ReentrantLock());
        if (!lock.tryLock()) {
            log.debug("Token refresh already in progress: {}", tokenKey);
            return;
        }
        
        try {
            AuthStrategy strategy = strategies.get(oldToken.strategy());
            if (strategy == null) {
                log.error("Unknown strategy: {}", oldToken.strategy());
                return;
            }
            
            AuthConfig config = new AuthConfig(
                oldToken.strategy(),
                tokenKey,
                60,
                10,
                oldToken.refreshConfig()
            );
            
            TokenInfo newToken = strategy.refresh(config);
            tokenStore.storeToken(tokenKey, newToken);
            
            meterRegistry.counter("ph_auth_refresh_total",
                "strategy", oldToken.strategy(),
                "status", "success"
            ).increment();
            
            log.info("Token refreshed: {}", tokenKey);
            
        } catch (Exception e) {
            log.error("Token refresh failed: {}", tokenKey, e);
            meterRegistry.counter("ph_auth_refresh_total",
                "status", "error"
            ).increment();
        } finally {
            lock.unlock();
        }
    }
}
