package io.pockethive.worker.sdk.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.RedisTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Redis-backed token storage.
 */
public class RedisTokenStore {
    
    private final RedisTemplate<String, String> redis;
    private final ObjectMapper objectMapper;
    
    public RedisTokenStore(RedisTemplate<String, String> redis) {
        this.redis = redis;
        this.objectMapper = new ObjectMapper();
    }
    
    /**
     * Gets token from Redis.
     */
    public Optional<TokenInfo> getToken(String key) {
        try {
            String json = redis.opsForValue().get(key);
            if (json == null) {
                return Optional.empty();
            }
            TokenInfo token = objectMapper.readValue(json, TokenInfo.class);
            return Optional.of(token);
        } catch (Exception e) {
            return Optional.empty();
        }
    }
    
    /**
     * Stores token in Redis with TTL.
     */
    public void storeToken(String key, TokenInfo token) {
        try {
            String json = objectMapper.writeValueAsString(token);
            long ttl = token.expiresAt() - Instant.now().getEpochSecond() + 120; // Grace period
            if (ttl > 0) {
                redis.opsForValue().set(key, json, Duration.ofSeconds(ttl));
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to store token", e);
        }
    }
    
    /**
     * Deletes token from Redis.
     */
    public void deleteToken(String key) {
        redis.delete(key);
    }
    
    /**
     * Schedules token refresh.
     */
    public void scheduleRefresh(String swarmInstanceId, String tokenKey, long refreshAt) {
        String scheduleKey = "phauth:" + swarmInstanceId + ":schedule";
        redis.opsForZSet().add(scheduleKey, tokenKey, refreshAt);
    }
}
