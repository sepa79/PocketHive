package io.pockethive.tcpmock.config;

import io.pockethive.auth.client.AuthServiceClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.util.matcher.RequestMatcher;

/**
 * Responsibility: compose the selected PocketHive administration identity provider. Must not:
 * accept native credentials or substitute authority on failure. Contract:
 * RESP-TCP-MOCK-AUTHENTICATION —
 * docs/architecture/runtime-responsibilities.md#resp-tcp-mock-authentication.
 */
@Configuration
@ConditionalOnProperty(name = "tcp-mock.auth.provider", havingValue = "POCKETHIVE")
@EnableConfigurationProperties(TcpMockAuthProperties.class)
public class PocketHiveSecurityConfig {
  @Bean
  public AuthServiceClient authServiceClient(TcpMockAuthProperties settings) {
    return new AuthServiceClient(
        settings.serviceUrl(), settings.connectTimeout(), settings.readTimeout());
  }

  @Bean
  public SecurityFilterChain pocketHiveChain(
      HttpSecurity http, RequestMatcher publicRequests, AuthServiceClient client) throws Exception {
    SecurityConfig.prepare(http, publicRequests);
    http.addFilterBefore(new TcpMockAuthFilter(client, publicRequests), AuthorizationFilter.class);
    return http.build();
  }
}
