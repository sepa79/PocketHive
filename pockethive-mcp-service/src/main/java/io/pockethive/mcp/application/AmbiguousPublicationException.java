package io.pockethive.mcp.application;

public final class AmbiguousPublicationException extends RuntimeException {
    public AmbiguousPublicationException(String attemptId, Throwable cause) {
        super("PUBLICATION_RESULT_AMBIGUOUS: " + attemptId, cause);
    }
}
