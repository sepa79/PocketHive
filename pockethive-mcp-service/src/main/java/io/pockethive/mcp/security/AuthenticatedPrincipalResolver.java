package io.pockethive.mcp.security;

import io.pockethive.mcp.domain.PrincipalKey;
import java.net.URI;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.BearerTokenAuthentication;
import org.springframework.stereotype.Component;

/**
 * Responsibility: Resolve a validated principal key from the authenticated ingress identity.
 * Must not: Grant scopes, infer identities, or bypass the Auth Service token contract.
 * Contract: docs/mcp/README.md and docs/architecture/AUTH_SERVICE_API_SPEC.md.
 */

@Component
public final class AuthenticatedPrincipalResolver {
    public PrincipalKey resolve(Authentication authentication) {
        if (!(authentication instanceof BearerTokenAuthentication bearer)) {
            throw new IllegalStateException("MCP_AUTH_CONTEXT_MISSING");
        }
        String issuer = required(bearer.getTokenAttributes().get("iss"), "iss");
        String subject = required(bearer.getTokenAttributes().get("sub"), "sub");
        return new PrincipalKey(URI.create(issuer), subject);
    }

    private static String required(Object value, String field) {
        if (value == null || String.valueOf(value).isBlank()) {
            throw new IllegalStateException("MCP_AUTH_CONTEXT_MISSING: " + field);
        }
        return String.valueOf(value);
    }
}
