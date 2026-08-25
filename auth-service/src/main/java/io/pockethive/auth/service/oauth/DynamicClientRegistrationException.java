package io.pockethive.auth.service.oauth;

import org.springframework.http.HttpStatus;

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
