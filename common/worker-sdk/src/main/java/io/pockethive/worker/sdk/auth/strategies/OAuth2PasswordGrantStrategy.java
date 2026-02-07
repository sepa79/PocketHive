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
import java.util.HashMap;
import java.util.Map;

public class OAuth2PasswordGrantStrategy implements AuthStrategy {
    
    private final RestTemplate restTemplate;
    
    public OAuth2PasswordGrantStrategy(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    @Override
    public String getType() {
        return "oauth2-password-grant";
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
	        Map<?, ?> response = restTemplate.postForObject(tokenUrl, request, Map.class);
	        if (response == null || !response.containsKey("access_token")) {
	            throw new IllegalStateException("OAuth2 response missing access_token");
	        }

	        String accessToken = String.valueOf(response.get("access_token"));
	        Object tokenTypeObj = response.get("token_type");
	        String tokenType = tokenTypeObj == null ? "Bearer" : tokenTypeObj.toString();
	        Object expiresObj = response.get("expires_in");
	        int expiresIn = expiresObj instanceof Number number ? number.intValue() : 3600;
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
            tokenType,
            expiresAt,
            refreshAt,
            getType(),
            refreshConfig,
            metadata
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
