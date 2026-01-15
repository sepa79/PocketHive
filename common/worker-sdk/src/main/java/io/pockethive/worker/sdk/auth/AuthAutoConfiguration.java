package io.pockethive.worker.sdk.auth;

import io.micrometer.core.instrument.MeterRegistry;
import io.pockethive.worker.sdk.auth.strategies.ApiKeyStrategy;
import io.pockethive.worker.sdk.auth.strategies.AwsSignatureV4Strategy;
import io.pockethive.worker.sdk.auth.strategies.BasicAuthStrategy;
import io.pockethive.worker.sdk.auth.strategies.BearerTokenStrategy;
import io.pockethive.worker.sdk.auth.strategies.HmacSignatureStrategy;
import io.pockethive.worker.sdk.auth.strategies.Iso8583MacStrategy;
import io.pockethive.worker.sdk.auth.strategies.MessageFieldAuthStrategy;
import io.pockethive.worker.sdk.auth.strategies.OAuth2ClientCredentialsStrategy;
import io.pockethive.worker.sdk.auth.strategies.OAuth2PasswordGrantStrategy;
import io.pockethive.worker.sdk.auth.strategies.StaticTokenStrategy;
import io.pockethive.worker.sdk.auth.strategies.TlsClientCertStrategy;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.Map;

/**
 * Auto-configuration for PocketHive auth system.
 */
@Configuration
@EnableConfigurationProperties(AuthProperties.class)
@ConditionalOnProperty(prefix = "pockethive.auth", name = "enabled", havingValue = "true", matchIfMissing = true)
public class AuthAutoConfiguration {
    
    @Bean
    public RestTemplate authRestTemplate(AuthProperties properties) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.getHttp().getConnectTimeoutSeconds() * 1000);
        factory.setReadTimeout(properties.getHttp().getReadTimeoutSeconds() * 1000);
        return new RestTemplate(factory);
    }
    
    @Bean
    public InMemoryTokenStore tokenStore() {
        return new InMemoryTokenStore();
    }
    
    @Bean
    public AuthConfigRegistry authConfigRegistry() {
        return new AuthConfigRegistry();
    }
    
    @Bean
    public Map<String, AuthStrategy> authStrategies(RestTemplate authRestTemplate) {
        Map<String, AuthStrategy> strategies = new HashMap<>();
        strategies.put("bearer-token", new BearerTokenStrategy());
        strategies.put("basic-auth", new BasicAuthStrategy());
        strategies.put("api-key", new ApiKeyStrategy());
        strategies.put("oauth2-client-credentials", new OAuth2ClientCredentialsStrategy(authRestTemplate));
        strategies.put("oauth2-password-grant", new OAuth2PasswordGrantStrategy(authRestTemplate));
        strategies.put("hmac-signature", new HmacSignatureStrategy());
        strategies.put("tls-client-cert", new TlsClientCertStrategy());
        strategies.put("message-field-auth", new MessageFieldAuthStrategy());
        strategies.put("static-token", new StaticTokenStrategy());
        strategies.put("aws-signature-v4", new AwsSignatureV4Strategy());
        strategies.put("iso8583-mac", new Iso8583MacStrategy());
        return strategies;
    }
    
    @Bean
    public AuthHeaderGenerator authHeaderGenerator(
        InMemoryTokenStore tokenStore,
        Map<String, AuthStrategy> strategies,
        AuthConfigRegistry authConfigRegistry
    ) {
        return new AuthHeaderGenerator(tokenStore, strategies, authConfigRegistry);
    }
    
    @Bean
    @ConditionalOnProperty(prefix = "pockethive.auth.scheduler", name = "enabled", havingValue = "true", matchIfMissing = true)
    public TokenRefreshScheduler tokenRefreshScheduler(
        InMemoryTokenStore tokenStore,
        Map<String, AuthStrategy> strategies,
        MeterRegistry meterRegistry
    ) {
        return new TokenRefreshScheduler(tokenStore, strategies, meterRegistry);
    }
    
    @Configuration
    @EnableScheduling
    @ConditionalOnProperty(prefix = "pockethive.auth.scheduler", name = "enabled", havingValue = "true", matchIfMissing = true)
    static class SchedulingConfiguration {
    }
}
