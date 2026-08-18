package io.pockethive.mcp.adapter.mcp;

import io.modelcontextprotocol.common.McpTransportContext;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.security.oauth2.server.resource.authentication.BearerTokenAuthentication;

public final class McpTransportContextFactory {
    private McpTransportContextFactory() {
    }

    public static McpTransportContext from(HttpServletRequest request) {
        if (!(request.getUserPrincipal() instanceof BearerTokenAuthentication authentication)) {
            return McpTransportContext.EMPTY;
        }
        Map<String, Object> attributes = authentication.getTokenAttributes();
        Map<String, Object> context = new LinkedHashMap<>();
        context.put(McpCaller.ISSUER, String.valueOf(attributes.get("iss")));
        context.put(McpCaller.SUBJECT, String.valueOf(attributes.get("sub")));
        context.put(McpCaller.CLIENT_ID, String.valueOf(attributes.get("client_id")));
        context.put(McpCaller.SCOPES, String.valueOf(attributes.get("scope")));
        return McpTransportContext.create(context);
    }
}
