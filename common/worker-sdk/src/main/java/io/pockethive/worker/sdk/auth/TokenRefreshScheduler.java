package io.pockethive.worker.sdk.auth;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Background scheduler for proactive token refresh.
 */
public class TokenRefreshScheduler {
    
    private static final Logger log = LoggerFactory.getLogger(TokenRefreshScheduler.class);
    
    private final RedisTemplate<String, String> redis;
    private final RedisTokenStore tokenStore;
    private final Map<String, AuthStrategy> strategies;
    private final MeterRegistry meterRegistry;
    private final String instanceId;
    
    public TokenRefreshScheduler(
        RedisTemplate<String, String> redis,
        RedisTokenStore tokenStore,
        Map<String, AuthStrategy> strategies,
        MeterRegistry meterRegistry,
        String instanceId
    ) {
        this.redis = redis;
        this.tokenStore = tokenStore;
        this.strategies = strategies;
        this.meterRegistry = meterRegistry;
        this.instanceId = instanceId;
    }
    
    @Scheduled(fixedDelay = 10000) // Every 10 seconds
    public void scanAndRefresh() {
        try {
            Map<Object, Object> instances = redis.opsForHash().entries("phauth:instances");
            
            for (Object instanceIdObj : instances.keySet()) {
                String swarmInstanceId = instanceIdObj.toString();
                scanInstanceTokens(swarmInstanceId);
            }
        } catch (Exception e) {
            log.error("Token refresh scan failed", e);
        }
    }
    
    private void scanInstanceTokens(String swarmInstanceId) {
        long now = Instant.now().getEpochSecond();
        String scheduleKey = "phauth:" + swarmInstanceId + ":schedule";
        
        try {
            Set<String> tokenKeys = redis.opsForZSet().rangeByScore(scheduleKey, 0, now);
            
            if (tokenKeys != null) {
                for (String tokenKey : tokenKeys) {
                    CompletableFuture.runAsync(() -> refreshToken(swarmInstanceId, tokenKey));
                }
            }
        } catch (Exception e) {
            log.error("Failed to scan tokens for instance: {}", swarmInstanceId, e);
        }
    }
    
    private void refreshToken(String swarmInstanceId, String tokenKey) {
        String lockKey = "phauth:" + swarmInstanceId + ":lock:" + tokenKey;
        
        try {
            Boolean acquired = redis.opsForValue().setIfAbsent(lockKey, instanceId, Duration.ofSeconds(30));
            
            if (Boolean.FALSE.equals(acquired)) {
                log.debug("Lock held by another instance: {}", lockKey);
                return;
            }
            
            try {
                String storeKey = "phauth:" + swarmInstanceId + ":token:" + tokenKey;
                TokenInfo oldToken = tokenStore.getToken(storeKey).orElse(null);
                
                if (oldToken == null) {
                    log.warn("Token not found for refresh: {}", tokenKey);
                    return;
                }
                
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
                tokenStore.storeToken(storeKey, newToken);
                tokenStore.scheduleRefresh(swarmInstanceId, tokenKey, newToken.refreshAt());
                
                updateActivity(swarmInstanceId);
                
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
                redis.delete(lockKey);
            }
        } catch (RedisConnectionFailureException e) {
            log.error("Redis unavailable during lock acquisition", e);
        }
    }
    
    private void updateActivity(String swarmInstanceId) {
        try {
            redis.opsForHash().put("phauth:instances", swarmInstanceId,
                "{\"lastActivity\":" + Instant.now().getEpochSecond() + "}");
        } catch (Exception e) {
            log.warn("Failed to update activity for: {}", swarmInstanceId, e);
        }
    }
}
