package io.pockethive.auth.client;

/**
 * Responsibility: Carry a safe OAuth error code without upstream text or credentials.
 * Must not: Retry, convert denial into identity or expose upstream response bodies.
 * Contract: docs/architecture/BROWSER_DEVICE_LOGIN.md#shared-consumer-boundary.
 */
public final class DeviceAuthException extends AuthServiceClientException {
    private final String error;

    DeviceAuthException(String error, int status) {
        super("PH device authorization failed", status, null);
        this.error = error;
    }

    public String getError() { return error; }
}
