package io.pockethive.mcp.application;

public final class UploadRejectedException extends RuntimeException {
    public UploadRejectedException(String code) {
        super(code);
    }

    public UploadRejectedException(String code, Throwable cause) {
        super(code, cause);
    }
}
