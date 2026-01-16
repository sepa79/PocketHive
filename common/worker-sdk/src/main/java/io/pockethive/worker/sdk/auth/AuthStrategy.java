package io.pockethive.worker.sdk.auth;

import io.pockethive.worker.sdk.api.WorkItem;

import java.util.Map;

/**
 * Strategy for generating authorization headers.
 */
public interface AuthStrategy {
    
    /**
     * Returns the auth type this strategy handles.
     */
    String getType();
    
    /**
     * Generates authorization headers.
     */
    Map<String, String> generateHeaders(AuthConfig config, TokenInfo token, WorkItem item);
    
    /**
     * Checks if token needs refresh.
     */
    default boolean requiresRefresh(TokenInfo token, AuthConfig config) {
        return token == null || token.needsRefresh();
    }
    
    /**
     * Refreshes the token (for OAuth2, etc.).
     */
    default TokenInfo refresh(AuthConfig config) {
        throw new UnsupportedOperationException("Token refresh not supported for " + getType());
    }
}
