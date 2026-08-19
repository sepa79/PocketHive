package io.pockethive.mcp.application;

public final class ToolExecutionException extends RuntimeException {
    private final String code;

    public ToolExecutionException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
