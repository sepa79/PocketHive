package io.pockethive.mcp.config;
import io.pockethive.mcp.config.McpStateMode;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class PocketHiveMcpPropertiesTest {
    @Test
    void distinguishesSecurePublicIdentityFromFixedInternalOwnerRouting() {
        PocketHiveMcpProperties properties = properties(
            URI.create("https://lab.example"), URI.create("http://ui:8088"), 10, 2);

        assertThat(properties.hasAllowedPublicEndpoints()).isTrue();
        assertThat(properties.hasValidOwnerApiBase()).isTrue();
        assertThat(properties.pocketHiveIngress()).isNotEqualTo(properties.ownerApiBase());
    }

    @Test
    void rejectsInvalidPublicOwnerProtocolAndLimitConfiguration() {
        assertThat(properties(URI.create("http://lab.example"), URI.create("http://ui:8088"), 10, 2)
            .hasAllowedPublicEndpoints()).isFalse();
        assertThat(properties(URI.create("https://lab.example"), URI.create("file:///tmp/owner"), 10, 2)
            .hasValidOwnerApiBase()).isFalse();
        assertThat(properties(URI.create("https://lab.example"), URI.create("http://ui:8088/path"), 10, 2)
            .hasValidOwnerApiBase()).isFalse();
        assertThat(properties(URI.create("https://lab.example"), URI.create("http://ui:8088"), 2, 3)
            .hasConsistentLimits()).isFalse();
    }

    @Test
    void explicitlyAllowsRemoteHttpOnlyWhenEnabled() {
        PocketHiveMcpProperties properties = properties(URI.create("http://lab.example:8088"),
            URI.create("http://ui:8088"), 10, 2, true);
        assertThat(properties.hasAllowedPublicEndpoints()).isTrue();
        assertThat(properties(URI.create("ftp://lab.example"), URI.create("http://ui:8088"), 10, 2, true)
            .hasAllowedPublicEndpoints()).isFalse();
    }

    private static PocketHiveMcpProperties properties(URI ingress, URI owner, int totalSessions,
                                                       int sessionsPerPrincipal) {
        return properties(ingress, owner, totalSessions, sessionsPerPrincipal, false);
    }

    private static PocketHiveMcpProperties properties(URI ingress, URI owner, int totalSessions,
                                                       int sessionsPerPrincipal, boolean allowRemoteHttp) {
        return new PocketHiveMcpProperties(
            allowRemoteHttp, ingress, owner, McpStateMode.MEMORY,
            Path.of("target/state"), Path.of("target/spool"),
            Duration.ofHours(1), Duration.ofHours(1), Duration.ofHours(1), Duration.ofHours(1),
            Duration.ofMinutes(5), totalSessions, sessionsPerPrincipal, 100, 10, 1_000_000,
            2, 10, 100_000, 200_000, 20, 200_000, 8, 100,
            List.of(ingress.toString()), List.of(ingress.getHost()), ingress,
            URI.create(ingress + "/mcp"), URI.create("http://auth-service:8080/oauth/introspect"),
            "mcp", "secret-secret-secret", "pockethive-mcp", "service-secret-secret");
    }
}
