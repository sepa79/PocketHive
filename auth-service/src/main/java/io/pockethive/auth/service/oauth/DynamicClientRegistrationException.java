package io.pockethive.auth.service.oauth;

import org.springframework.http.HttpStatus;

/**
 * Responsibility: Represent a safe dynamic-client registration refusal.
 * Must not: Bypass canonical scope policy, client authentication, or Spring Authorization Server contracts.
 * Contract: docs/architecture/AUTH_SERVICE_API_SPEC.md and docs/AUTH-BEHAVIOR.md.
 */

final class DynamicClientRegistrationException extends RuntimeException {
    private final String error;
    private final HttpStatus status;

    DynamicClientRegistrationException(String error, String description) {
        this(error, description, HttpStatus.BAD_REQUEST);
    }

    DynamicClientRegistrationException(String error, String description, HttpStatus status) {
        super(description);
        this.error = error;
        this.status = status;
    }

    String error() {
        return error;
    }

    HttpStatus status() {
        return status;
    }
}
