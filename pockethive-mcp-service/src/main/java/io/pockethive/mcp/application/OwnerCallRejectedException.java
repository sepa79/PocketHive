package io.pockethive.mcp.application;

public final class OwnerCallRejectedException extends RuntimeException {
    public OwnerCallRejectedException(String message, Throwable cause) {
        super(message, cause);
    }
}
