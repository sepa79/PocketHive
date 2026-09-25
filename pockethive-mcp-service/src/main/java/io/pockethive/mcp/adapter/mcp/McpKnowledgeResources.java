package io.pockethive.mcp.adapter.mcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import io.pockethive.mcp.application.EnvironmentHealthService;
import io.pockethive.mcp.application.McpKnowledgeProjection;
import io.pockethive.mcp.application.SkillDescriptor;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Responsibility: Publish canonical PocketHive knowledge, capability, and environment resources
 * over MCP. Must not: Own domain state transitions or reinterpret owner-service outcomes. Contract:
 * docs/mcp/README.md.
 */
@Component
public final class McpKnowledgeResources {
  private static final String JSON = "application/json";
  private static final String MARKDOWN = "text/markdown";

  private final McpKnowledgeProjection projection;
  private final EnvironmentHealthService environmentHealth;
  private final ObjectMapper mapper;
  private final List<String> supportedProtocolRevisions;

  public McpKnowledgeResources(
      McpKnowledgeProjection projection,
      EnvironmentHealthService environmentHealth,
      ObjectMapper mapper,
      HttpServletStreamableServerTransportProvider transport) {
    this.projection = projection;
    this.environmentHealth = environmentHealth;
    this.mapper = mapper;
    this.supportedProtocolRevisions = List.copyOf(transport.protocolVersions());
  }

  public List<McpServerFeatures.SyncResourceSpecification> specifications() {
    List<McpServerFeatures.SyncResourceSpecification> resources = new ArrayList<>();
    resources.add(
        jsonResource(
            "pockethive://knowledge/overview",
            "PocketHive overview",
            "PocketHive authority boundaries and safe first actions.",
            McpKnowledgeProjection.overview()));
    resources.add(
        jsonResource(
            "pockethive://knowledge/glossary",
            "PocketHive glossary",
            "Canonical PocketHive terms needed by a repository with no local PocketHive context.",
            McpKnowledgeProjection.glossary()));
    projection
        .documents()
        .forEach(
            document ->
                resources.add(
                    resource(
                        document.uri(),
                        document.title(),
                        "Immutable projection of " + document.sourcePath(),
                        MARKDOWN,
                        document.markdown())));
    resources.add(
        dynamicJsonResource(
            "pockethive://capabilities/current",
            "Current PocketHive capabilities",
            "Authenticated MCP identity, immutable binding, and descriptor fingerprint.",
            exchange ->
                projection.currentCapabilities(
                    McpCallerDecoder.from(exchange.transportContext()),
                    supportedProtocolRevisions)));
    resources.add(
        dynamicJsonResource(
            "pockethive://tools/catalogue",
            "PocketHive tool catalogue",
            "Canonical agent-facing tool descriptors visible to this grant.",
            exchange ->
                projection.visibleTools(McpCallerDecoder.from(exchange.transportContext()))));
    resources.add(
        dynamicJsonResource(
            "pockethive://skills/catalogue",
            "PocketHive connected skills",
            "Versioned skill index connected to tools visible to this grant.",
            exchange ->
                projection.visibleSkills(McpCallerDecoder.from(exchange.transportContext()))));
    resources.add(
        dynamicJsonResource(
            "pockethive://environment/health",
            "PocketHive environment health",
            "Bounded health projection for canonical services behind this environment's public"
                + " ingress.",
            exchange -> environmentHealth.read()));
    projection.skills().forEach(skill -> resources.add(markdownResource(skill)));
    return List.copyOf(resources);
  }

  public String catalogueDigest() {
    return projection.catalogueDigest();
  }

  private McpServerFeatures.SyncResourceSpecification jsonResource(
      String uri, String name, String description, Object value) {
    return resource(uri, name, description, JSON, json(value));
  }

  private McpServerFeatures.SyncResourceSpecification markdownResource(SkillDescriptor skill) {
    return resource(
        skill.resourceUri(), skill.name(), skill.description(), MARKDOWN, skill.markdown());
  }

  private McpServerFeatures.SyncResourceSpecification dynamicJsonResource(
      String uri,
      String name,
      String description,
      java.util.function.Function<io.modelcontextprotocol.server.McpSyncServerExchange, Object>
          value) {
    McpSchema.Resource resource =
        McpSchema.Resource.builder()
            .uri(uri)
            .name(name)
            .description(description)
            .mimeType(JSON)
            .build();
    return new McpServerFeatures.SyncResourceSpecification(
        resource,
        (exchange, request) ->
            new McpSchema.ReadResourceResult(
                List.of(
                    new McpSchema.TextResourceContents(uri, JSON, json(value.apply(exchange))))));
  }

  private static McpServerFeatures.SyncResourceSpecification resource(
      String uri, String name, String description, String mimeType, String content) {
    McpSchema.Resource resource =
        McpSchema.Resource.builder()
            .uri(uri)
            .name(name)
            .description(description)
            .mimeType(mimeType)
            .build();
    return new McpServerFeatures.SyncResourceSpecification(
        resource,
        (exchange, request) ->
            new McpSchema.ReadResourceResult(
                List.of(new McpSchema.TextResourceContents(uri, mimeType, content))));
  }

  private String json(Object value) {
    try {
      return mapper.writeValueAsString(value);
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("MCP_RESOURCE_SERIALIZATION_FAILED", exception);
    }
  }
}
