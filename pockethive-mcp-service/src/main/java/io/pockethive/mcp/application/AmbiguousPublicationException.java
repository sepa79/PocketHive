package io.pockethive.mcp.application;

/**
 * Responsibility: Represent the explicit ambiguous publication application failure.
 * Must not: Depend on HTTP, MCP transport, or persistence implementations.
 * Contract: docs/mcp/README.md.
 */

public final class AmbiguousPublicationException extends RuntimeException {
    private final String attemptId;

    public AmbiguousPublicationException(String attemptId, Throwable cause) {
        super("PUBLICATION_RESULT_AMBIGUOUS: " + attemptId, cause);
        this.attemptId = attemptId;
    }

    public String attemptId() {
        return attemptId;
    }
}
