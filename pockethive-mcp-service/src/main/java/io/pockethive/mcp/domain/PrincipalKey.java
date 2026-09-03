package io.pockethive.mcp.domain;

import java.net.URI;

/**
 * Responsibility: Model the PrincipalKey MCP domain concept and enforce its local invariants.
 * Must not: Access transport, configuration, or infrastructure adapters.
 * Contract: docs/mcp/README.md.
 */

public record PrincipalKey(URI issuer, String subject) {
    public PrincipalKey {
        if (issuer == null || !issuer.isAbsolute()) {
            throw new IllegalArgumentException("issuer must be an absolute URI");
        }
        subject = requireText(subject, "subject");
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}
