package io.pockethive.worker.sdk.auth;

import io.micrometer.core.instrument.MeterRegistry;
import io.pockethive.worker.sdk.auth.strategies.ApiKeyStrategy;
import io.pockethive.worker.sdk.auth.strategies.BasicAuthStrategy;
import io.pockethive.worker.sdk.auth.strategies.BearerTokenStrategy;
import io.pockethive.worker.sdk.auth.strategies.HmacSignatureStrategy;
import io.pockethive.worker.sdk.auth.strategies.MessageFieldAuthStrategy;
import io.pockethive.worker.sdk.auth.strategies.OAuth2ClientCredentialsStrategy;
import io.pockethive.worker.sdk.auth.strategies.StaticTokenStrategy;
import io.pockethive.worker.sdk.auth.strategies.TlsClientCertStrategy;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Auto-configuration for PocketHive auth system.
 */
@Configuration
@EnableConfigurationProperties(AuthProperties.class)
@ConditionalOnProperty(prefix = "pockethive.auth", name = "enabled", havingValue = "true")
public class AuthAutoConfiguration {
    
    @Bean
    public RestTemplate authRestTemplate(AuthProperties properties) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.getHttp().getConnectTimeoutSeconds() * 1000);
        factory.setReadTimeout(properties.getHttp().getReadTimeoutSeconds() * 1000);
        return new RestTemplate(factory);
    }
    
    @Bean
    public RedisTokenStore redisTokenStore(RedisTemplate<String, String> redis) {
        return new RedisTokenStore(redis);
    }
    
    @Bean
    public Map<String, AuthStrategy> authStrategies(RestTemplate authRestTemplate) {
        Map<String, AuthStrategy> strategies = new HashMap<>();
        strategies.put("bearer-token", new BearerTokenStrategy());
        strategies.put("basic-auth", new BasicAuthStrategy());
        strategies.put("api-key", new ApiKeyStrategy());
        strategies.put("oauth2-client-credentials", new OAuth2ClientCredentialsStrategy(authRestTemplate));
        strategies.put("hmac-signature", new HmacSignatureStrategy());
        strategies.put("tls-client-cert", new TlsClientCertStrategy());
        strategies.put("message-field-auth", new MessageFieldAuthStrategy());
        strategies.put("static-token", new StaticTokenStrategy());
        return strategies;
    }
    
    @Bean
    public AuthHeaderGenerator authHeaderGenerator(
        RedisTokenStore tokenStore,
        Map<String, AuthStrategy> strategies
    ) {
        return new AuthHeaderGenerator(tokenStore, strategies);
    }
    
    @Bean
    @ConditionalOnProperty(prefix = "pockethive.auth.scheduler", name = "enabled", havingValue = "true", matchIfMissing = true)
    public TokenRefreshScheduler tokenRefreshScheduler(
        RedisTemplate<String, String> redis,
        RedisTokenStore tokenStore,
        Map<String, AuthStrategy> strategies,
        MeterRegistry meterRegistry
    ) {
        String instanceId = UUID.randomUUID().toString();
        return new TokenRefreshScheduler(redis, tokenStore, strategies, meterRegistry, instanceId);
    }
    
    @Configuration
    @EnableScheduling
    @ConditionalOnProperty(prefix = "pockethive.auth.scheduler", name = "enabled", havingValue = "true", matchIfMissing = true)
    static class SchedulingConfiguration {
    }
}
