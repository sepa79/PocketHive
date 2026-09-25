package io.pockethive.mcp.adapter.mcp;

import io.modelcontextprotocol.common.McpTransportContext;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.security.oauth2.server.resource.authentication.BearerTokenAuthentication;

/**
 * Responsibility: Create an MCP transport context from authenticated ingress request attributes.
 * Must not: Own domain state transitions or reinterpret owner-service outcomes. Contract:
 * docs/mcp/README.md.
 */
public final class McpTransportContextFactory {
  private McpTransportContextFactory() {}

  public static McpTransportContext from(HttpServletRequest request) {
    if (!(request.getUserPrincipal() instanceof BearerTokenAuthentication authentication)) {
      return McpTransportContext.EMPTY;
    }
    Map<String, Object> attributes = authentication.getTokenAttributes();
    Map<String, Object> context = new LinkedHashMap<>();
    context.put(McpCallerDecoder.ISSUER, String.valueOf(attributes.get("iss")));
    context.put(McpCallerDecoder.SUBJECT, String.valueOf(attributes.get("sub")));
    context.put(McpCallerDecoder.PRINCIPAL_LABEL, String.valueOf(attributes.get("username")));
    context.put(McpCallerDecoder.CLIENT_ID, String.valueOf(attributes.get("client_id")));
    context.put(McpCallerDecoder.SCOPES, String.valueOf(attributes.get("scope")));
    return McpTransportContext.create(context);
  }
}
