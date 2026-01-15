package io.pockethive.worker.sdk.auth.strategies;

import io.pockethive.worker.sdk.api.WorkItem;
import io.pockethive.worker.sdk.auth.AuthConfig;
import io.pockethive.worker.sdk.auth.AuthStrategy;
import io.pockethive.worker.sdk.auth.TokenInfo;

import java.util.HashMap;
import java.util.Map;

/**
 * TLS client certificate strategy for mutual TLS authentication.
 * Provides certificate paths that the TCP client should use for connection.
 */
public class TlsClientCertStrategy implements AuthStrategy {
    
    @Override
    public String getType() {
        return "tls-client-cert";
    }
    
    @Override
    public Map<String, String> generateHeaders(AuthConfig config, TokenInfo token, WorkItem item) {
        String certPath = config.properties().get("certPath");
        String keyPath = config.properties().get("keyPath");
        String keyPassword = config.properties().get("keyPassword");
        
        if (certPath == null || keyPath == null) {
            throw new IllegalArgumentException("certPath and keyPath are required for TLS client cert auth");
        }
        
        Map<String, String> headers = new HashMap<>();
        headers.put("x-ph-tls-cert-path", certPath);
        headers.put("x-ph-tls-key-path", keyPath);
        if (keyPassword != null) {
            headers.put("x-ph-tls-key-password", keyPassword);
        }
        
        return headers;
    }
}
