package io.pockethive.mcp.config;

import io.pockethive.auth.client.AuthServiceClient;
import io.pockethive.auth.client.AuthServiceServiceTokenProvider;
import java.net.URI;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * Responsibility: Wire MCP owner-service authentication to the shared Auth Service client and token owner.
 * Must not: Cache tokens, authorize calls, or infer an alternative ingress or service identity.
 * Contract: docs/mcp/README.md and docs/architecture/AUTH_SERVICE_API_SPEC.md.
 */

@Configuration
@EnableConfigurationProperties(McpOwnerAuthProperties.class)
public class McpOwnerAuthConfiguration {
    private static final String AUTH_SERVICE_INGRESS_PATH = "/auth-service";

    @Bean
    AuthServiceClient ownerAuthServiceClient(PocketHiveMcpProperties properties, McpOwnerAuthProperties deadlines) {
        URI authServiceIngress = properties.ownerApiBase().resolve(AUTH_SERVICE_INGRESS_PATH);
        return new AuthServiceClient(authServiceIngress, deadlines.connectTimeout(), deadlines.readTimeout());
    }

    @Bean
    AuthServiceServiceTokenProvider ownerServiceTokenProvider(AuthServiceClient ownerAuthServiceClient,
                                                              PocketHiveMcpProperties properties) {
        return new AuthServiceServiceTokenProvider(
            ownerAuthServiceClient,
            properties.downstreamServiceName(),
            properties.downstreamServiceSecret());
    }
}
