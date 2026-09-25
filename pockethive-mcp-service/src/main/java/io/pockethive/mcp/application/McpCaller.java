package io.pockethive.mcp.application;

import io.pockethive.mcp.domain.PrincipalKey;
import java.util.Objects;
import java.util.Set;

/**
 * Responsibility: carry immutable authenticated caller identity to application handlers. Must not:
 * depend on MCP transport, grant scopes or decide owner permissions. Contract:
 * RESP-MCP-CLIENT-INTERACTION —
 * docs/architecture/runtime-responsibilities.md#resp-mcp-client-interaction.
 */
public record McpCaller(
    PrincipalKey principal, String principalLabel, String clientId, Set<String> scopes) {
  public McpCaller {
    Objects.requireNonNull(principal);
    Objects.requireNonNull(principalLabel);
    Objects.requireNonNull(clientId);
    scopes = Set.copyOf(scopes);
  }

  public boolean hasScope(String scope) {
    return scopes.contains(scope);
  }

  public void requireScope(String scope) {
    if (!hasScope(scope)) throw new ToolExecutionException("MCP_SCOPE_REQUIRED", scope);
  }
}
