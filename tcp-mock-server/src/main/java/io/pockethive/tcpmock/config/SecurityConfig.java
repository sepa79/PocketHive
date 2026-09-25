package io.pockethive.tcpmock.config;

import java.util.Arrays;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

/**
 * Responsibility: compose the administrative bearer security chain and shared auth client. Must
 * not: own credentials, workspace policy or TCP traffic authentication. Contract:
 * RESP-TCP-MOCK-AUTHENTICATION —
 * docs/architecture/runtime-responsibilities.md#resp-tcp-mock-authentication.
 */
@Configuration
@EnableConfigurationProperties(TcpMockAuthSelection.class)
public class SecurityConfig {
  private static final String[] PUBLIC_PATHS = {
    "/",
    "/index.html",
    "/*.js",
    "/*.css",
    "/*.html",
    "/css/**",
    "/js/**",
    "/images/**",
    "/docs/**",
    "/actuator/health",
    "/api/auth/config"
  };

  @Bean
  public RequestMatcher publicRequests() {
    return new OrRequestMatcher(
        Arrays.stream(PUBLIC_PATHS)
            .map(path -> (RequestMatcher) new AntPathRequestMatcher(path))
            .toList());
  }

  static void prepare(HttpSecurity http, RequestMatcher publicRequests) throws Exception {
    http.csrf(AbstractHttpConfigurer::disable)
        .httpBasic(AbstractHttpConfigurer::disable)
        .formLogin(AbstractHttpConfigurer::disable)
        .sessionManagement(
            session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(
            auth ->
                auth.dispatcherTypeMatchers(jakarta.servlet.DispatcherType.ERROR)
                    .permitAll()
                    .requestMatchers(publicRequests)
                    .permitAll()
                    .anyRequest()
                    .authenticated())
        .exceptionHandling(
            errors ->
                errors.authenticationEntryPoint(
                    (request, response, error) -> response.sendError(401)));
  }
}
