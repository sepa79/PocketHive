package io.pockethive.auth.contract;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.util.List;
import org.junit.jupiter.api.Test;

class PublicEndpointTransportPolicyTest {
    @Test
    void requiresExplicitPermissionForRemoteHttpAndKeepsSecureAndLoopbackEndpoints() {
        for (String endpoint : List.of("http://lab.example:8088/mcp", "http://192.0.2.1/auth-service")) {
            assertThat(PublicEndpointTransportPolicy.allows(URI.create(endpoint), false)).isFalse();
            assertThat(PublicEndpointTransportPolicy.allows(URI.create(endpoint), true)).isTrue();
        }
        for (String endpoint : List.of("https://lab.example/mcp", "http://localhost:8088/mcp",
            "http://127.0.0.1/auth-service", "http://[::1]:8088/mcp")) {
            assertThat(PublicEndpointTransportPolicy.allows(URI.create(endpoint), false)).as(endpoint).isTrue();
        }
    }

    @Test
    void permissionNeverAllowsMalformedOrNonHttpEndpoints() {
        assertThat(PublicEndpointTransportPolicy.allows(null, true)).isFalse();
        for (String endpoint : List.of("/mcp", "file:///tmp/mcp", "ftp://lab.example/mcp",
            "http://user@lab.example/mcp", "http://lab.example/mcp?q=1", "https://lab.example/#fragment",
            "http://localhost.evil/mcp")) {
            assertThat(PublicEndpointTransportPolicy.allows(URI.create(endpoint), false)).as(endpoint).isFalse();
            if (!endpoint.equals("http://localhost.evil/mcp")) {
                assertThat(PublicEndpointTransportPolicy.allows(URI.create(endpoint), true)).as(endpoint).isFalse();
            }
        }
    }
}
