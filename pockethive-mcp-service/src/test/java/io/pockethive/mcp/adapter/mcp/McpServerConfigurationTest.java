package io.pockethive.mcp.adapter.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.pockethive.mcp.application.EnvironmentHealthContract;
import io.pockethive.mcp.application.EnvironmentHealthService;
import io.pockethive.mcp.application.EnvironmentHealthTarget;
import io.pockethive.mcp.application.McpToolExecutor;
import io.pockethive.mcp.application.ToolCatalogue;
import io.pockethive.mcp.config.PocketHiveMcpProperties;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.boot.info.BuildProperties;
import java.util.Properties;

class McpServerConfigurationTest {
    @Test
    void publishesCanonicalToolsAndPortableKnowledgeOnTheExactProtocolEndpoint() {
        McpServerConfiguration configuration = new McpServerConfiguration();
        PocketHiveMcpProperties properties = properties();
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        ToolCatalogue catalogue = ToolCatalogue.canonical();
        EnvironmentHealthService environmentHealth = new EnvironmentHealthService(
            properties.pocketHiveIngress(),
            List.of(new EnvironmentHealthTarget("ui", "UI", URI.create("/"), "/healthz",
                EnvironmentHealthContract.PLAIN_OK)),
            target -> true,
            Clock.systemUTC());
        HttpServletStreamableServerTransportProvider transport = configuration.transport(objectMapper, properties);
        McpKnowledgeResources resources = new McpKnowledgeResources(
            catalogue, environmentHealth, properties, objectMapper, Clock.systemUTC(), transport);
        Properties buildValues = new Properties();
        buildValues.setProperty("version", "9.8.7-test");
        McpSyncServer server = configuration.server(transport, catalogue, resources,
            mock(McpToolExecutor.class), objectMapper, new BuildProperties(buildValues));
        ServletRegistrationBean<HttpServletStreamableServerTransportProvider> servlet =
            configuration.mcpServlet(transport);

        assertThat(server.getServerInfo().name()).isEqualTo("pockethive-mcp");
        assertThat(server.getServerInfo().version()).isEqualTo("9.8.7-test");
        assertThat(server.listTools()).extracting(tool -> tool.name())
            .containsExactlyElementsOf(catalogue.tools().stream().map(tool -> tool.id()).toList());
        assertThat(server.listResources()).extracting(resource -> resource.uri())
            .contains("pockethive://knowledge/overview", "pockethive://knowledge/glossary",
                "pockethive://knowledge/architecture", "pockethive://knowledge/scenario-contract",
                "pockethive://knowledge/worker-capabilities", "pockethive://knowledge/orchestrator-rest",
                "pockethive://knowledge/scenario-manager-bundle-rest",
                "pockethive://knowledge/correlation-idempotency",
                "pockethive://capabilities/current", "pockethive://tools/catalogue",
                "pockethive://skills/catalogue", "pockethive://environment/health");
        assertThat(resources.catalogueDigest()).matches("sha256:[0-9a-f]{64}");
        assertThat(servlet.getUrlMappings()).containsExactly("/mcp");
        server.close();
    }

    private static PocketHiveMcpProperties properties() {
        URI ingress = URI.create("http://127.0.0.1:8080");
        return new PocketHiveMcpProperties(
            ingress, ingress, PocketHiveMcpProperties.StateMode.MEMORY,
            Path.of("target/state"), Path.of("target/spool"), Duration.ofMinutes(30),
            Duration.ofHours(1), Duration.ofHours(1), Duration.ofHours(1), Duration.ofMinutes(5),
            100, 10, 100, 10, 10_000_000, 2, 10,
            10_000_000, 20_000_000, 200, 20_000_000, 8, 100,
            List.of("http://127.0.0.1:8080"), List.of("127.0.0.1:8080"),
            ingress, URI.create("http://127.0.0.1:8080/mcp"),
            URI.create("http://127.0.0.1:8080/oauth/introspect"), "mcp", "secret",
            "pockethive-mcp", "service-secret");
    }
}
