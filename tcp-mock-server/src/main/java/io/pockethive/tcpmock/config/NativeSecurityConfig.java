package io.pockethive.tcpmock.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.util.matcher.RequestMatcher;

/**
 * Responsibility: compose the selected local Basic-auth administration provider. Must not:
 * instantiate auth-service clients or switch providers on failure. Contract:
 * RESP-TCP-MOCK-AUTHENTICATION —
 * docs/architecture/runtime-responsibilities.md#resp-tcp-mock-authentication.
 */
@Configuration
@ConditionalOnProperty(name = "tcp-mock.auth.provider", havingValue = "NATIVE")
@EnableConfigurationProperties(TcpMockNativeAuthProperties.class)
public class NativeSecurityConfig {
  @Bean
  public UserDetailsService nativeUsers(TcpMockNativeAuthProperties settings) {
    return new InMemoryUserDetailsManager(
        User.withUsername(settings.username())
            .password("{noop}" + settings.password())
            .authorities(java.util.List.of())
            .build());
  }

  @Bean
  public SecurityFilterChain nativeChain(HttpSecurity http, RequestMatcher publicRequests)
      throws Exception {
    SecurityConfig.prepare(http, publicRequests);
    http.httpBasic(Customizer.withDefaults());
    return http.build();
  }
}
