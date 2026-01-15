package io.pockethive.worker.sdk.auth;

import io.pockethive.worker.sdk.api.WorkItem;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Authorization configuration with two-phase variable resolution.
 */
public record AuthConfig(
    String type,
    String tokenKey,
    int refreshBuffer,
    int emergencyRefreshBuffer,
    Map<String, String> properties
) {
    
    /**
     * Creates AuthConfig from template map (Phase 1: resolve static variables only).
     */
    public static AuthConfig fromTemplate(
        Map<String, Object> authSection,
        String serviceId,
        String callId
    ) {
        String type = (String) authSection.get("type");
        if (type == null) {
            throw new IllegalArgumentException("auth.type is required");
        }
        
        AuthType authType = AuthType.parse(type);
        
        String tokenKey = (String) authSection.getOrDefault("tokenKey", serviceId + ":" + callId);
        int refreshBuffer = ((Number) authSection.getOrDefault("refreshBuffer", 60)).intValue();
        int emergencyBuffer = ((Number) authSection.getOrDefault("emergencyRefreshBuffer", 10)).intValue();
        
        Map<String, String> properties = new HashMap<>();
        authSection.forEach((key, value) -> {
            if (!key.equals("type") && !key.equals("tokenKey") && 
                !key.equals("refreshBuffer") && !key.equals("emergencyRefreshBuffer")) {
                properties.put(key, resolveStaticVariable(value.toString()));
            }
        });
        
        validateAuthType(authType, properties);
        
        return new AuthConfig(type, tokenKey, refreshBuffer, emergencyBuffer, properties);
    }
    
    /**
     * Resolves dynamic variables (Phase 2: headers from WorkItem).
     */
    public AuthConfig resolveHeaders(WorkItem item) {
        Map<String, String> resolved = new HashMap<>(properties);
        properties.forEach((key, value) -> {
            if (value != null && value.startsWith("${header:")) {
                String headerName = value.substring(10, value.length() - 1);
                Object headerObj = item.headers().get(headerName);
                if (headerObj != null) {
                    resolved.put(key, headerObj.toString());
                }
            }
        });
        return new AuthConfig(type, tokenKey, refreshBuffer, emergencyRefreshBuffer, resolved);
    }
    
    private static String resolveStaticVariable(String value) {
        if (value == null || !value.startsWith("${") || !value.endsWith("}")) {
            return value;
        }
        
        String expr = value.substring(2, value.length() - 1);
        String[] parts = expr.split(":", 2);
        if (parts.length != 2) return value;
        
        String source = parts[0];
        String name = parts[1];
        
        return switch (source) {
            case "env" -> System.getenv(name);
            case "file" -> {
                try {
                    yield Files.readString(Path.of(name)).trim();
                } catch (IOException e) {
                    throw new IllegalArgumentException("Failed to read file: " + name, e);
                }
            }
            case "header" -> value;
            default -> value;
        };
    }
    
    private static void validateAuthType(AuthType type, Map<String, String> props) {
        switch (type) {
            case OAUTH2_CLIENT_CREDENTIALS:
                requireField(props, "tokenUrl", "clientId", "clientSecret");
                break;
            case OAUTH2_PASSWORD_GRANT:
                requireField(props, "tokenUrl", "username", "password");
                break;
            case BASIC_AUTH:
                requireField(props, "username", "password");
                break;
            case BEARER_TOKEN:
                requireField(props, "token");
                break;
            case API_KEY:
                requireField(props, "key");
                break;
            case HMAC_SIGNATURE:
                requireField(props, "secretKey");
                break;
            case TLS_CLIENT_CERT:
                requireField(props, "certPath", "keyPath");
                break;
            case STATIC_TOKEN:
                requireField(props, "token");
                break;
            case AWS_SIGNATURE_V4:
                requireField(props, "accessKeyId", "secretAccessKey", "region", "service");
                break;
            case ISO8583_MAC:
                requireField(props, "macKey");
                break;
        }
    }
    
    private static void requireField(Map<String, String> props, String... fields) {
        for (String field : fields) {
            if (!props.containsKey(field) || props.get(field) == null) {
                throw new IllegalArgumentException("Required field missing: " + field);
            }
        }
    }
}
