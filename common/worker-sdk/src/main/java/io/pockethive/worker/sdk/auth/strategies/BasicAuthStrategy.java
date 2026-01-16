package io.pockethive.worker.sdk.auth.strategies;

import io.pockethive.worker.sdk.api.WorkItem;
import io.pockethive.worker.sdk.auth.AuthConfig;
import io.pockethive.worker.sdk.auth.AuthStrategy;
import io.pockethive.worker.sdk.auth.TokenInfo;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

/**
 * Basic authentication strategy.
 */
public class BasicAuthStrategy implements AuthStrategy {
    
    @Override
    public String getType() {
        return "basic-auth";
    }
    
    @Override
    public Map<String, String> generateHeaders(AuthConfig config, TokenInfo token, WorkItem item) {
        String username = config.properties().get("username");
        String password = config.properties().get("password");
        
        if (username == null || password == null) {
            throw new IllegalArgumentException("Username and password are required for Basic auth");
        }
        
        String credentials = username + ":" + password;
        String encoded = Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
        
        return Map.of("Authorization", "Basic " + encoded);
    }

    @Override
    public boolean requiresRefresh(TokenInfo token, AuthConfig config) {
        return false;
    }
}
