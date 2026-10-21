package io.pockethive.auth.client;

/**
 * Responsibility: Carry a safe shared auth transport failure and HTTP status.
 * Must not: Expose credentials or implement authorization/retry policy.
 * Contract: docs/architecture/BROWSER_DEVICE_LOGIN.md#shared-consumer-boundary.
 */
public class AuthServiceClientException extends RuntimeException {
    private final int statusCode;

    public AuthServiceClientException(String message, int statusCode, Throwable cause) {
        super(message, cause);
        this.statusCode = statusCode;
    }

    public int getStatusCode() {
        return statusCode;
    }
}
