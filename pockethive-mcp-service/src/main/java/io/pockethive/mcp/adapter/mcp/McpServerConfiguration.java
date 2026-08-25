package io.pockethive.mcp.adapter.mcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.DefaultServerTransportSecurityValidator;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import io.pockethive.mcp.application.McpToolExecutor;
import io.pockethive.mcp.application.ToolCatalogue;
import io.pockethive.mcp.application.ToolDescriptor;
import io.pockethive.mcp.config.PocketHiveMcpProperties;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class McpServerConfiguration {
    private static final String SERVER_NAME = "pockethive-mcp";

    @Bean
    ToolCatalogue toolCatalogue() {
        return ToolCatalogue.canonical();
    }

    @Bean
    HttpServletStreamableServerTransportProvider transport(ObjectMapper objectMapper,
                                                            PocketHiveMcpProperties properties) {
        JacksonMcpJsonMapper mapper = new JacksonMcpJsonMapper(objectMapper);
        DefaultServerTransportSecurityValidator validator =
            DefaultServerTransportSecurityValidator.builder()
                .allowedOrigins(properties.allowedOrigins())
                .allowedHosts(properties.allowedHosts())
                .build();
        return HttpServletStreamableServerTransportProvider.builder()
            .jsonMapper(mapper)
            .mcpEndpoint("/mcp")
            .disallowDelete(false)
            .contextExtractor(McpTransportContextFactory::from)
            .keepAliveInterval(Duration.ofSeconds(20))
            .securityValidator(validator)
            .build();
    }

    @Bean(destroyMethod = "close")
    McpSyncServer server(HttpServletStreamableServerTransportProvider transport,
                         ToolCatalogue catalogue,
                         McpKnowledgeResources resources,
                         McpToolExecutor executor,
                         ToolFailureMapper failureMapper,
                         ObjectMapper objectMapper,
                         BuildProperties buildProperties) {
        JacksonMcpJsonMapper mapper = new JacksonMcpJsonMapper(objectMapper);
        List<McpServerFeatures.SyncToolSpecification> tools = catalogue.tools().stream()
            .map(descriptor -> tool(descriptor, executor, failureMapper, objectMapper))
            .toList();
        return McpServer.sync(transport)
            .serverInfo(SERVER_NAME, buildProperties.getVersion())
            .instructions("Read pockethive://knowledge/overview, pockethive://capabilities/current, and pockethive://skills/catalogue before operating PocketHive.")
            .capabilities(McpSchema.ServerCapabilities.builder()
                .tools(false)
                .resources(false, false)
                .build())
            .jsonMapper(mapper)
            .strictToolNameValidation(true)
            .validateToolInputs(true)
            .immediateExecution(true)
            .tools(tools)
            .resources(resources.specifications())
            .build();
    }

    @Bean
    ServletRegistrationBean<HttpServletStreamableServerTransportProvider> mcpServlet(
        HttpServletStreamableServerTransportProvider transport) {
        ServletRegistrationBean<HttpServletStreamableServerTransportProvider> registration =
            new ServletRegistrationBean<>(transport, "/mcp");
        registration.setName("pockethiveMcpTransport");
        registration.setLoadOnStartup(1);
        return registration;
    }

    private static McpServerFeatures.SyncToolSpecification tool(ToolDescriptor descriptor,
                                                                 McpToolExecutor executor,
                                                                 ToolFailureMapper failureMapper,
                                                                 ObjectMapper mapper) {
        McpSchema.ToolAnnotations annotations = McpSchema.ToolAnnotations.builder()
            .readOnlyHint(descriptor.readOnly())
            .destructiveHint(descriptor.destructive())
            .idempotentHint(descriptor.idempotent())
            .openWorldHint(true)
            .build();
        McpSchema.Tool tool = McpSchema.Tool.builder()
            .name(descriptor.id())
            .description(descriptor.description())
            .inputSchema(descriptor.inputSchema())
            .outputSchema(descriptor.outputSchema())
            .annotations(annotations)
            .meta(Map.of(
                "owner", descriptor.owner().name(),
                "requiredScope", descriptor.requiredScope(),
                "skills", descriptor.skillIds()))
            .build();
        return new McpServerFeatures.SyncToolSpecification(tool, (exchange, request) -> {
            try {
                Object result = executor.execute(descriptor, exchange, request.arguments());
                Object structuredContent = ToolStructuredContent.normalize(mapper, result);
                ToolOutputValidator.validate(descriptor, structuredContent);
                return McpSchema.CallToolResult.builder()
                    .addTextContent(json(mapper, structuredContent))
                    .structuredContent(structuredContent)
                    .isError(false)
                    .build();
            } catch (RuntimeException exception) {
                KnownToolFailure failure = failureMapper.known(exception)
                    .orElseThrow(() -> failureMapper.unexpected(descriptor.id(), exception));
                Map<String, Object> error = failure.structuredContent();
                return McpSchema.CallToolResult.builder()
                    .addTextContent(json(mapper, error))
                    .structuredContent(error)
                    .isError(true)
                    .build();
            }
        });
    }

    private static String json(ObjectMapper mapper, Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("TOOL_RESULT_SERIALIZATION_FAILED", exception);
        }
    }
}
