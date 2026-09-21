package io.pockethive.mcp.security;
import io.pockethive.mcp.config.McpStateMode;

import static org.assertj.core.api.Assertions.assertThat;

import io.pockethive.auth.contract.PocketHiveMcpScopes;
import io.pockethive.mcp.config.PocketHiveMcpProperties;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.BadCredentialsException;

class McpAuthenticationEntryPointTest {
    @Test
    void protectedResourceMetadataUsesTheSamePortableInteractiveScopeContract() {
        Map<String, Object> metadata = new ProtectedResourceMetadataController(properties()).metadata();

        assertThat(metadata).containsExactlyInAnyOrderEntriesOf(Map.of(
            "resource", "https://lab.example/mcp",
            "authorization_servers", List.of("https://lab.example"),
            "scopes_supported", PocketHiveMcpScopes.COMPANION_ORDERED,
            "bearer_methods_supported", List.of("header")));
    }

    @Test
    void advertisesPortableOauthDiscoveryAndScopesWithoutClientSpecificBehavior() throws Exception {
        McpAuthenticationEntryPoint entryPoint = new McpAuthenticationEntryPoint(properties());
        MockHttpServletResponse response = new MockHttpServletResponse();

        entryPoint.commence(new MockHttpServletRequest("POST", "/mcp"), response,
            new BadCredentialsException("redacted"));

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getHeader(HttpHeaders.WWW_AUTHENTICATE)).isEqualTo(
            "Bearer resource_metadata=\"https://lab.example/.well-known/oauth-protected-resource\", scope=\""
                + String.join(" ", PocketHiveMcpScopes.COMPANION_ORDERED) + "\"");
        assertThat(response.getContentType()).isEqualTo("application/json");
        assertThat(response.getContentAsString()).isEqualTo("{\"code\":\"MCP_AUTHENTICATION_REQUIRED\"}");
    }

    private static PocketHiveMcpProperties properties() {
        URI ingress = URI.create("https://lab.example");
        return new PocketHiveMcpProperties(
            ingress, URI.create("http://ui:8088"), McpStateMode.MEMORY,
            Path.of("target/state"), Path.of("target/spool"),
            Duration.ofHours(1), Duration.ofHours(1), Duration.ofHours(1), Duration.ofHours(1),
            Duration.ofMinutes(5), 10, 2, 100, 10, 1_000_000,
            2, 10, 100_000, 200_000, 20, 200_000, 8, 100,
            List.of(ingress.toString()), List.of(ingress.getHost()), ingress,
            URI.create(ingress + "/mcp"), URI.create("http://auth-service:8080/oauth/introspect"),
            "mcp", "secret-secret-secret", "pockethive-mcp", "service-secret-secret");
    }
}
