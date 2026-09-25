package io.pockethive.mcp.adapter.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.mcp.application.KnowledgeDocument;
import io.pockethive.mcp.application.KnowledgeDocumentSource;
import io.pockethive.mcp.application.McpKnowledgeProjection;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Responsibility: describe bundled canonical knowledge for application projections. Must not:
 * execute tools or decide owner-service outcomes. Contract: RESP-MCP-KNOWLEDGE-PROJECTION —
 * docs/architecture/runtime-responsibilities.md#resp-mcp-knowledge-projection.
 */
@Component
public final class ClasspathKnowledgeDocuments implements KnowledgeDocumentSource {
  private final ObjectMapper mapper;

  public ClasspathKnowledgeDocuments(ObjectMapper mapper) {
    this.mapper = mapper;
  }

  public List<KnowledgeDocument> documents() {
    return List.of(
        document(
            "architecture", "PocketHive architecture", "docs/ARCHITECTURE.md", "ARCHITECTURE.md"),
        document(
            "scenario-contract",
            "Scenario Bundle contract",
            "docs/scenarios/SCENARIO_CONTRACT.md",
            "scenarios/SCENARIO_CONTRACT.md"),
        document(
            "worker-capabilities",
            "Worker capability catalogue contract",
            "docs/architecture/workerCapabilities.md",
            "architecture/workerCapabilities.md"),
        document(
            "orchestrator-rest",
            "Orchestrator public API",
            "docs/ORCHESTRATOR-REST.md",
            "ORCHESTRATOR-REST.md"),
        document(
            "scenario-manager-bundle-rest",
            "Scenario Manager bundle API",
            "docs/scenarios/SCENARIO_MANAGER_BUNDLE_REST.md",
            "scenarios/SCENARIO_MANAGER_BUNDLE_REST.md"),
        document(
            "correlation-idempotency",
            "Correlation and idempotency rules",
            "docs/correlation-vs-idempotency.md",
            "correlation-vs-idempotency.md"));
  }

  private KnowledgeDocument document(
      String id, String title, String sourcePath, String classpathPath) {
    String markdown;
    try (InputStream input =
        getClass().getClassLoader().getResourceAsStream("pockethive-docs/" + classpathPath)) {
      if (input == null) {
        throw new IllegalStateException("MCP_KNOWLEDGE_RESOURCE_MISSING: " + sourcePath);
      }
      markdown = new String(input.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException exception) {
      throw new IllegalStateException(
          "MCP_KNOWLEDGE_RESOURCE_READ_FAILED: " + sourcePath, exception);
    }
    return new KnowledgeDocument(
        "pockethive://knowledge/" + id,
        title,
        sourcePath,
        McpKnowledgeProjection.digest(mapper, markdown),
        markdown);
  }
}
