package io.pockethive.mcp.application;

import java.util.Map;

/**
 * Responsibility: expose client form capability, elicitation and metadata to QA workflows. Must
 * not: select workflow transitions or grant permission. Contract: RESP-MCP-CLIENT-INTERACTION —
 * docs/architecture/runtime-responsibilities.md#resp-mcp-client-interaction.
 */
public interface ClientInteraction {
  boolean supportsForm();

  /** Returns a decoded non-null result, or throws ToolExecutionException for invalid client output. */
  ClientElicitationResult elicit(String message, Map<String, Object> schema);

  String clientName();

  String clientVersion();
}
