package io.pockethive.mcp.application;

public final class UploadAuthenticationException extends RuntimeException {
    public UploadAuthenticationException(String code) {
        super(code);
    }
}
