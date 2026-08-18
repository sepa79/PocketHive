package io.pockethive.mcp.application;

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
