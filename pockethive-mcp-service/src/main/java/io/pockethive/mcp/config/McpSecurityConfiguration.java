package io.pockethive.mcp.config;

import io.pockethive.mcp.security.McpOpaqueTokenIntrospector;
import io.pockethive.mcp.security.McpProtocolSecurityFilter;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.introspection.OpaqueTokenIntrospector;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;

@Configuration
public class McpSecurityConfiguration {
    @Bean
    OpaqueTokenIntrospector opaqueTokenIntrospector(PocketHiveMcpProperties properties) {
        return new McpOpaqueTokenIntrospector(properties);
    }

    @Bean
    @Order(1)
    SecurityFilterChain protectedMcpRoutes(HttpSecurity http, OpaqueTokenIntrospector introspector,
                                           PocketHiveMcpProperties properties, Clock clock) throws Exception {
        var paths = PathPatternRequestMatcher.withDefaults();
        return http
            .securityMatcher(new OrRequestMatcher(paths.matcher("/mcp"), paths.matcher("/mcp/**")))
            .authorizeHttpRequests(authorize -> authorize.anyRequest().authenticated())
            .oauth2ResourceServer(oauth -> oauth.opaqueToken(token -> token.introspector(introspector)))
            .addFilterAfter(new McpProtocolSecurityFilter(properties.protocolRevision(), clock,
                    properties.openSessionTtl(), properties.maxTransportSessions()),
                BearerTokenAuthenticationFilter.class)
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .csrf(csrf -> csrf.disable())
            .build();
    }

    @Bean
    @Order(2)
    SecurityFilterChain publicMetadataAndHealth(HttpSecurity http) throws Exception {
        return http
            .authorizeHttpRequests(authorize -> authorize
                .requestMatchers("/.well-known/oauth-protected-resource", "/actuator/health", "/actuator/info")
                .permitAll()
                .anyRequest().denyAll())
            .httpBasic(Customizer.withDefaults())
            .csrf(csrf -> csrf.disable())
            .build();
    }
}
