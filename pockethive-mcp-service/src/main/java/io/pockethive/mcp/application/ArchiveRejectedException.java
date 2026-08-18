package io.pockethive.mcp.application;

public final class ArchiveRejectedException extends RuntimeException {
    public ArchiveRejectedException(String code) {
        super(code);
    }

    public ArchiveRejectedException(String code, Throwable cause) {
        super(code, cause);
    }
}
