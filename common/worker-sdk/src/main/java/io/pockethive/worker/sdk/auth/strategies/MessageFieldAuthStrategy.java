package io.pockethive.worker.sdk.auth.strategies;

import io.pockethive.worker.sdk.api.WorkItem;
import io.pockethive.worker.sdk.auth.AuthConfig;
import io.pockethive.worker.sdk.auth.AuthStrategy;
import io.pockethive.worker.sdk.auth.TokenInfo;

import java.util.HashMap;
import java.util.Map;

/**
 * Message field authentication strategy for protocols like ISO-8583.
 * Injects authentication credentials into specific message fields.
 */
public class MessageFieldAuthStrategy implements AuthStrategy {
    
    @Override
    public String getType() {
        return "message-field-auth";
    }
    
    @Override
    public Map<String, String> generateHeaders(AuthConfig config, TokenInfo token, WorkItem item) {
        Map<String, String> fields = new HashMap<>();
        
        config.properties().forEach((key, value) -> {
            if (key.startsWith("field.")) {
                String fieldName = key.substring(6);
                fields.put("x-ph-auth-field-" + fieldName, value);
            }
        });
        
        if (fields.isEmpty()) {
            throw new IllegalArgumentException("At least one field.* property is required for message field auth");
        }
        
        return fields;
    }

    @Override
    public boolean requiresRefresh(TokenInfo token, AuthConfig config) {
        return false;
    }
}
