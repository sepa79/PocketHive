package io.pockethive.mcp.adapter.mcp;

import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import io.pockethive.mcp.application.*;
import io.pockethive.mcp.domain.ElicitationAction;
import java.util.Map;
import java.util.Objects;

/**
 * Responsibility: adapt SDK client capabilities, form results and metadata to the application port.
 * Must not: own QA acceptance, scope policy or workflow mutations. Contract:
 * RESP-MCP-CLIENT-INTERACTION —
 * docs/architecture/runtime-responsibilities.md#resp-mcp-client-interaction.
 */
public final class McpClientInteraction implements ClientInteraction {
  private final McpSyncServerExchange exchange;

  public McpClientInteraction(McpSyncServerExchange exchange) {
    this.exchange = Objects.requireNonNull(exchange);
  }

  public boolean supportsForm() {
    return exchange.getClientCapabilities() != null
        && exchange.getClientCapabilities().elicitation() != null
        && exchange.getClientCapabilities().elicitation().form() != null;
  }

  public ClientElicitationResult elicit(String message, Map<String, Object> schema) {
    var result =
        exchange.createElicitation(McpSchema.ElicitFormRequest.builder(message, schema).build());
    if (result == null || result.action() == null)
      throw new ToolExecutionException(
          "ELICITATION_RESULT_INVALID", "The client returned no elicitation result");
    return new ClientElicitationResult(
        ElicitationAction.valueOf(result.action().name()), result.content());
  }

  public String clientName() {
    return exchange.getClientInfo() == null ? "unknown" : exchange.getClientInfo().name();
  }

  public String clientVersion() {
    return exchange.getClientInfo() == null ? "unknown" : exchange.getClientInfo().version();
  }
}
