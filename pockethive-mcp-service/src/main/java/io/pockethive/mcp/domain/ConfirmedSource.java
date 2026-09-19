package io.pockethive.mcp.domain;

import java.util.Objects;

/**
 * Responsibility: Model the ConfirmedSource MCP domain concept and enforce its local invariants.
 * Must not: Access transport, configuration, or infrastructure adapters.
 * Contract: docs/mcp/README.md.
 */

public record ConfirmedSource(String name, String digest) {
    private static final java.util.regex.Pattern SHA_256 =
        java.util.regex.Pattern.compile("sha256:[0-9a-f]{64}");

    public ConfirmedSource {
        name = requireText(name, "name");
        digest = Objects.requireNonNull(digest, "digest");
        if (!SHA_256.matcher(digest).matches()) {
            throw new IllegalArgumentException("confirmed source digest must be canonical SHA-256");
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}
