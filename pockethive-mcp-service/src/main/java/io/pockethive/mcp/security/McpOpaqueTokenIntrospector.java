package io.pockethive.mcp.security;

import io.pockethive.mcp.config.PocketHiveMcpProperties;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.DefaultOAuth2AuthenticatedPrincipal;
import org.springframework.security.oauth2.core.OAuth2AuthenticatedPrincipal;
import org.springframework.security.oauth2.server.resource.introspection.BadOpaqueTokenException;
import org.springframework.security.oauth2.server.resource.introspection.OpaqueTokenIntrospector;
import org.springframework.security.oauth2.server.resource.introspection.SpringOpaqueTokenIntrospector;

public final class McpOpaqueTokenIntrospector implements OpaqueTokenIntrospector {
    private final OpaqueTokenIntrospector delegate;
    private final String expectedAudience;
    private final String expectedIssuer;
    private final Clock clock;

    public McpOpaqueTokenIntrospector(PocketHiveMcpProperties properties) {
        this(SpringOpaqueTokenIntrospector.withIntrospectionUri(
                properties.oauthIntrospectionUri().toString())
            .clientId(properties.oauthIntrospectionClientId())
            .clientSecret(properties.oauthIntrospectionClientSecret())
            .build(), properties.oauthResource().toString(), properties.oauthIssuer().toString(), Clock.systemUTC());
    }

    McpOpaqueTokenIntrospector(OpaqueTokenIntrospector delegate, String expectedAudience,
                               String expectedIssuer, Clock clock) {
        this.delegate = delegate;
        this.expectedAudience = expectedAudience;
        this.expectedIssuer = expectedIssuer;
        this.clock = clock;
    }

    @Override
    public OAuth2AuthenticatedPrincipal introspect(String token) {
        OAuth2AuthenticatedPrincipal principal = delegate.introspect(token);
        requireClaim(principal, "iss", expectedIssuer);
        String subject = requireTextClaim(principal, "sub");
        requireTextClaim(principal, "client_id");
        requireAudience(principal.getAttribute("aud"));
        Instant expiresAt = principal.getAttribute("exp");
        if (expiresAt == null || !expiresAt.isAfter(clock.instant())) {
            throw new BadOpaqueTokenException("MCP access token is expired or has no expiry");
        }
        List<String> scopes = scopes(principal.getAttribute("scope"));
        if (scopes.isEmpty()) {
            throw new BadOpaqueTokenException("MCP access token has no scopes");
        }
        Map<String, Object> attributes = new LinkedHashMap<>(principal.getAttributes());
        attributes.put("scope", String.join(" ", scopes));
        return new DefaultOAuth2AuthenticatedPrincipal(subject, attributes, scopeAuthorities(scopes));
    }

    private String requireTextClaim(OAuth2AuthenticatedPrincipal principal, String claim) {
        Object value = principal.getAttribute(claim);
        if (value == null || String.valueOf(value).isBlank()) {
            throw new BadOpaqueTokenException("MCP access token " + claim + " is missing");
        }
        return String.valueOf(value);
    }

    private void requireClaim(OAuth2AuthenticatedPrincipal principal, String claim, String expected) {
        Object actual = principal.getAttribute(claim);
        if (!expected.equals(String.valueOf(actual))) {
            throw new BadOpaqueTokenException("MCP access token " + claim + " mismatch");
        }
    }

    private void requireAudience(Object audience) {
        boolean matches = audience instanceof Collection<?> values
            ? values.stream().map(String::valueOf).anyMatch(expectedAudience::equals)
            : expectedAudience.equals(String.valueOf(audience));
        if (!matches) {
            throw new BadOpaqueTokenException("MCP access token audience mismatch");
        }
    }

    private static List<String> scopes(Object raw) {
        if (raw == null) {
            return List.of();
        }
        if (raw instanceof Collection<?> collection) {
            List<String> result = new ArrayList<>();
            for (Object value : collection) {
                String scope = String.valueOf(value).trim();
                if (!scope.isEmpty()) {
                    result.add(scope);
                }
            }
            return List.copyOf(result);
        }
        return Arrays.stream(String.valueOf(raw).trim().split("\\s+"))
            .filter(scope -> !scope.isBlank())
            .toList();
    }

    private static List<GrantedAuthority> scopeAuthorities(List<String> scopes) {
        return scopes.stream()
            .map(scope -> (GrantedAuthority) new SimpleGrantedAuthority("SCOPE_" + scope))
            .toList();
    }
}
