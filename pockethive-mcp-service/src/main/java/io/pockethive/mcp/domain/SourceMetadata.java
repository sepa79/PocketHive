package io.pockethive.mcp.domain;

import java.util.Objects;

/**
 * Responsibility: Model the SourceMetadata MCP domain concept and enforce its local invariants.
 * Must not: Access transport, configuration, or infrastructure adapters.
 * Contract: docs/mcp/README.md.
 */

public record SourceMetadata(String repository, String commit, String bundlePath,
                             SourceVerification verification) {
    public SourceMetadata {
        repository = required(repository, "repository");
        commit = required(commit, "commit");
        bundlePath = required(bundlePath, "bundlePath");
        Objects.requireNonNull(verification, "verification");
        if (!commit.matches("[0-9a-fA-F]{40}|[0-9a-fA-F]{64}")) {
            throw new IllegalArgumentException("SOURCE_COMMIT_INVALID");
        }
        if (bundlePath.startsWith("/") || bundlePath.contains("\\")
            || java.util.Arrays.asList(bundlePath.split("/", -1)).contains("..")) {
            throw new IllegalArgumentException("SOURCE_BUNDLE_PATH_INVALID");
        }
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}
