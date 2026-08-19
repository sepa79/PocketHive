package io.pockethive.mcp.application;

public final class OwnerCallAmbiguousException extends RuntimeException {
    public OwnerCallAmbiguousException(String message) {
        super(message);
    }

    public OwnerCallAmbiguousException(String message, Throwable cause) {
        super(message, cause);
    }
}
