package io.pockethive.mcp.application;

/**
 * Responsibility: Represent the explicit publication state sync application failure.
 * Must not: Depend on HTTP, MCP transport, or persistence implementations.
 * Contract: docs/mcp/README.md.
 */

/** Owner publication succeeded, but optional authoring-workflow receipt synchronization failed. */
public final class PublicationStateSyncException extends RuntimeException {
    private final String attemptId;

    public PublicationStateSyncException(String attemptId, Throwable cause) {
        super("PUBLICATION_SUCCEEDED_WORKFLOW_SYNC_FAILED:" + attemptId, cause);
        this.attemptId = attemptId;
    }

    public String attemptId() {
        return attemptId;
    }
}
