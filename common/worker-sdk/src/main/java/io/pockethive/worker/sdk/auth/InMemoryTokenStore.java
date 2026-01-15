package io.pockethive.worker.sdk.auth;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory token storage per worker instance.
 */
public class InMemoryTokenStore {
    
    private final Map<String, TokenInfo> tokens = new ConcurrentHashMap<>();
    
    public Optional<TokenInfo> getToken(String key) {
        TokenInfo token = tokens.get(key);
        if (token == null) {
            return Optional.empty();
        }
        if (token.expiresAt() <= Instant.now().getEpochSecond()) {
            tokens.remove(key);
            return Optional.empty();
        }
        return Optional.of(token);
    }
    
    public void storeToken(String key, TokenInfo token) {
        tokens.put(key, token);
    }
    
    public void deleteToken(String key) {
        tokens.remove(key);
    }
    
    public Map<String, TokenInfo> getAllTokens() {
        return Map.copyOf(tokens);
    }
}
