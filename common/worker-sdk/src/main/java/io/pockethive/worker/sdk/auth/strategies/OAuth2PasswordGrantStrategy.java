package io.pockethive.worker.sdk.auth.strategies;

import io.pockethive.worker.sdk.auth.AuthConfig;
import io.pockethive.worker.sdk.auth.AuthStrategy;
import io.pockethive.worker.sdk.auth.TokenInfo;
import io.pockethive.worker.sdk.api.WorkItem;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.util.Map;

public class OAuth2PasswordGrantStrategy implements AuthStrategy {
    
    private final RestTemplate restTemplate;
    
    public OAuth2PasswordGrantStrategy(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }
    
    @Override
    public TokenInfo refresh(AuthConfig config) {
        String tokenUrl = config.properties().get("tokenUrl");
        String username = config.properties().get("username");
        String password = config.properties().get("password");
        String scope = config.properties().get("scope");
        
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        
        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("grant_type", "password");
        body.add("username", username);
        body.add("password", password);
        if (scope != null) {
            body.add("scope", scope);
        }
        
        HttpEntity<MultiValueMap<String, String>> request = new HttpEntity<>(body, headers);
        Map<String, Object> response = restTemplate.postForObject(tokenUrl, request, Map.class);
        
        String accessToken = (String) response.get("access_token");
        int expiresIn = ((Number) response.getOrDefault("expires_in", 3600)).intValue();
        long now = Instant.now().getEpochSecond();
        
        return new TokenInfo(
            accessToken,
            now + expiresIn,
            now + expiresIn - config.refreshBuffer(),
            config.type(),
            config.properties()
        );
    }
    
    @Override
    public Map<String, String> generateHeaders(AuthConfig config, TokenInfo token, WorkItem item) {
        if (token == null) {
            return Map.of();
        }
        return Map.of("Authorization", "Bearer " + token.accessToken());
    }
}
