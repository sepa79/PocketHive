package io.pockethive.worker.sdk.auth.strategies;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.worker.sdk.api.WorkItem;
import io.pockethive.worker.sdk.auth.AuthConfig;
import io.pockethive.worker.sdk.auth.AuthStrategy;
import io.pockethive.worker.sdk.auth.TokenInfo;
import org.springframework.http.*;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * OAuth2 Client Credentials flow strategy.
 */
public class OAuth2ClientCredentialsStrategy implements AuthStrategy {
    
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    
    public OAuth2ClientCredentialsStrategy(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
        this.objectMapper = new ObjectMapper();
    }
    
    @Override
    public String getType() {
        return "oauth2-client-credentials";
    }
    
    @Override
    public Map<String, String> generateHeaders(AuthConfig config, TokenInfo token, WorkItem item) {
        if (token == null || token.accessToken() == null) {
            throw new IllegalStateException("Token not available");
        }
        return Map.of("Authorization", "Bearer " + token.accessToken());
    }
    
    @Override
    public TokenInfo refresh(AuthConfig config) {
        String tokenUrl = config.properties().get("tokenUrl");
        String clientId = config.properties().get("clientId");
        String clientSecret = config.properties().get("clientSecret");
        String scope = config.properties().get("scope");
        
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        
        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("grant_type", "client_credentials");
        body.add("client_id", clientId);
        body.add("client_secret", clientSecret);
        if (scope != null && !scope.isBlank()) {
            body.add("scope", scope);
        }
        
        HttpEntity<MultiValueMap<String, String>> request = new HttpEntity<>(body, headers);
        
        try {
            ResponseEntity<String> response = restTemplate.postForEntity(tokenUrl, request, String.class);
            
            if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                JsonNode json = objectMapper.readTree(response.getBody());
                
                String accessToken = json.get("access_token").asText();
                int expiresIn = json.has("expires_in") ? json.get("expires_in").asInt() : 3600;
                
                long now = Instant.now().getEpochSecond();
                long expiresAt = now + expiresIn;
                long refreshAt = expiresAt - config.refreshBuffer();
                
                Map<String, String> refreshConfig = new HashMap<>(config.properties());
                Map<String, Object> metadata = Map.of(
                    "lastRefreshed", now,
                    "refreshCount", 1,
                    "refreshBuffer", config.refreshBuffer(),
                    "emergencyRefreshBuffer", config.emergencyRefreshBuffer()
                );
                
                return new TokenInfo(
                    accessToken,
                    "Bearer",
                    expiresAt,
                    refreshAt,
                    getType(),
                    refreshConfig,
                    metadata
                );
            }
            
            throw new RuntimeException("Failed to refresh token: " + response.getStatusCode());
        } catch (Exception e) {
            throw new RuntimeException("OAuth2 token refresh failed", e);
        }
    }
}
