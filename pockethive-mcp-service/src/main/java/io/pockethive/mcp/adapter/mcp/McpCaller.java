package io.pockethive.mcp.adapter.mcp;

import io.modelcontextprotocol.common.McpTransportContext;
import io.pockethive.mcp.domain.PrincipalKey;
import java.net.URI;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Responsibility: Normalize the authenticated MCP transport identity into an immutable caller context.
 * Must not: Own domain state transitions or reinterpret owner-service outcomes.
 * Contract: docs/mcp/README.md.
 */

public record McpCaller(PrincipalKey principal, String principalLabel, String clientId, Set<String> scopes) {
    static final String ISSUER = "pockethive.issuer";
    static final String SUBJECT = "pockethive.subject";
    static final String PRINCIPAL_LABEL = "pockethive.principalLabel";
    static final String CLIENT_ID = "pockethive.clientId";
    static final String SCOPES = "pockethive.scopes";

    public static McpCaller from(McpTransportContext context) {
        String issuer = require(context, ISSUER);
        String subject = require(context, SUBJECT);
        String principalLabel = require(context, PRINCIPAL_LABEL);
        String clientId = require(context, CLIENT_ID);
        Set<String> scopes = Arrays.stream(require(context, SCOPES).split(" "))
            .filter(value -> !value.isBlank())
            .collect(Collectors.toUnmodifiableSet());
        return new McpCaller(new PrincipalKey(URI.create(issuer), subject), principalLabel, clientId, scopes);
    }

    private static String require(McpTransportContext context, String key) {
        Object value = context.get(key);
        if (value == null || String.valueOf(value).isBlank()) {
            throw new IllegalStateException("MCP_AUTH_CONTEXT_MISSING: " + key);
        }
        return String.valueOf(value);
    }
}
