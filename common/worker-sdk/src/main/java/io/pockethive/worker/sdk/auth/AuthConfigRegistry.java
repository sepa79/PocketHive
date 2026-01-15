package io.pockethive.worker.sdk.auth;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry for auth configurations accessible by tokenKey.
 */
public class AuthConfigRegistry {
    
    private final Map<String, AuthConfig> configs = new ConcurrentHashMap<>();
    
    /**
     * Registers auth configs from worker configuration.
     */
    public void registerFromConfig(List<Map<String, Object>> authList) {
        if (authList == null || authList.isEmpty()) {
            return;
        }
        
        for (Map<String, Object> authSection : authList) {
            String tokenKey = (String) authSection.get("tokenKey");
            if (tokenKey == null || tokenKey.isBlank()) {
                continue;
            }
            
            AuthConfig config = AuthConfig.fromTemplate(authSection, "default", tokenKey);
            configs.put(tokenKey, config);
        }
    }
    
    /**
     * Gets auth config by tokenKey.
     */
    public AuthConfig get(String tokenKey) {
        return configs.get(tokenKey);
    }
    
    /**
     * Clears all registered configs.
     */
    public void clear() {
        configs.clear();
    }
}
