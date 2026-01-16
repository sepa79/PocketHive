package io.pockethive.worker.sdk.auth.strategies;

import io.pockethive.worker.sdk.api.WorkItem;
import io.pockethive.worker.sdk.auth.AuthConfig;
import io.pockethive.worker.sdk.auth.AuthStrategy;
import io.pockethive.worker.sdk.auth.TokenInfo;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;

/**
 * HMAC signature strategy for TCP message authentication.
 */
public class HmacSignatureStrategy implements AuthStrategy {
    
    @Override
    public String getType() {
        return "hmac-signature";
    }
    
    @Override
    public Map<String, String> generateHeaders(AuthConfig config, TokenInfo token, WorkItem item) {
        String algorithm = config.properties().getOrDefault("algorithm", "HmacSHA256");
        String secretKey = config.properties().get("secretKey");
        String signatureField = config.properties().getOrDefault("signatureField", "x-ph-signature");
        
        if (secretKey == null) {
            throw new IllegalArgumentException("secretKey is required for HMAC signature");
        }
        
        try {
            Mac mac = Mac.getInstance(algorithm);
            SecretKeySpec keySpec = new SecretKeySpec(secretKey.getBytes(StandardCharsets.UTF_8), algorithm);
            mac.init(keySpec);
            
            byte[] signature = mac.doFinal(item.payload().getBytes(StandardCharsets.UTF_8));
            String signatureHex = HexFormat.of().formatHex(signature);
            
            Map<String, String> headers = new HashMap<>();
            headers.put(signatureField, signatureHex);
            headers.put("x-ph-signature-algorithm", algorithm);
            
            return headers;
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate HMAC signature", e);
        }
    }

    @Override
    public boolean requiresRefresh(TokenInfo token, AuthConfig config) {
        return false;
    }
}
