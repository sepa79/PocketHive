package io.pockethive.tcpmock.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfig {

  @Bean
  public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
    http.csrf(AbstractHttpConfigurer::disable)
      .authorizeHttpRequests(auth -> auth
        .requestMatchers(
          "/",
          "/index.html",
          "/index-complete.html",
          "/*.js",
          "/*.css",
          "/*.html",
          "/css/**",
          "/js/**",
          "/images/**",
          "/docs/**",
          "/actuator/health"
        ).permitAll()
        .anyRequest().authenticated())
      .httpBasic(httpBasic -> {})
      .exceptionHandling(ex -> ex
        .authenticationEntryPoint((request, response, authException) -> {
          response.setHeader("WWW-Authenticate", "Basic realm=\"TCP Mock Server\"");
          response.sendError(401, "Unauthorized");
        }));
    return http.build();
  }
}
