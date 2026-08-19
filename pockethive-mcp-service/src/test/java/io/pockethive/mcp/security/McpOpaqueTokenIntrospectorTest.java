package io.pockethive.mcp.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.DefaultOAuth2AuthenticatedPrincipal;
import org.springframework.security.oauth2.core.OAuth2AuthenticatedPrincipal;
import org.springframework.security.oauth2.server.resource.introspection.BadOpaqueTokenException;
import org.springframework.security.oauth2.server.resource.introspection.OpaqueTokenIntrospector;

class McpOpaqueTokenIntrospectorTest {
    private static final Instant NOW = Instant.parse("2026-08-18T12:00:00Z");
    private static final String ISSUER = "https://auth.example/auth-service";
    private static final String RESOURCE = "https://hive.example/mcp";

    @Test
    void validatesIdentityAndNormalizesScopesIntoAuthorities() {
        McpOpaqueTokenIntrospector introspector = introspector(claims());

        OAuth2AuthenticatedPrincipal principal = introspector.introspect("opaque-user-token");

        assertThat((String) principal.getAttribute("scope"))
            .isEqualTo("pockethive:mcp:read pockethive:mcp:operate");
        assertThat(principal.getAuthorities()).extracting("authority").containsExactlyInAnyOrder(
            "SCOPE_pockethive:mcp:read", "SCOPE_pockethive:mcp:operate");
    }

    @Test
    void rejectsIssuerAudienceExpiryAndMissingCanonicalIdentity() {
        assertRejected(with("iss", "https://wrong.example"));
        assertRejected(with("aud", List.of("https://another.example/mcp")));
        assertRejected(with("exp", NOW));
        assertRejected(with("sub", " "));
        assertRejected(with("client_id", null));
        assertRejected(with("username", null));
        assertRejected(with("scope", List.of()));
    }

    private static McpOpaqueTokenIntrospector introspector(Map<String, Object> claims) {
        OpaqueTokenIntrospector delegate = ignored ->
            new DefaultOAuth2AuthenticatedPrincipal("delegate", claims, List.of());
        return new McpOpaqueTokenIntrospector(
            delegate, RESOURCE, ISSUER, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static void assertRejected(Map<String, Object> claims) {
        assertThatThrownBy(() -> introspector(claims).introspect("token"))
            .isInstanceOf(BadOpaqueTokenException.class);
    }

    private static Map<String, Object> with(String name, Object value) {
        Map<String, Object> changed = new LinkedHashMap<>(claims());
        if (value == null) {
            changed.remove(name);
        } else {
            changed.put(name, value);
        }
        return changed;
    }

    private static Map<String, Object> claims() {
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("iss", ISSUER);
        claims.put("sub", "user-123");
        claims.put("client_id", "vscode-pockethive");
        claims.put("username", "qa-lead");
        claims.put("aud", List.of("another-audience", RESOURCE));
        claims.put("exp", NOW.plusSeconds(60));
        claims.put("scope", List.of("pockethive:mcp:read", "pockethive:mcp:operate"));
        return claims;
    }
}
