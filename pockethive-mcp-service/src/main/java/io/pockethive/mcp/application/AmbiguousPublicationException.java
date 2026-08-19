package io.pockethive.mcp.application;

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
