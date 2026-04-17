package io.pockethive.auth.client;

public final class AuthServiceClientException extends RuntimeException {
    private final int statusCode;

    public AuthServiceClientException(String message, int statusCode, Throwable cause) {
        super(message, cause);
        this.statusCode = statusCode;
    }

    public int getStatusCode() {
        return statusCode;
    }
}
