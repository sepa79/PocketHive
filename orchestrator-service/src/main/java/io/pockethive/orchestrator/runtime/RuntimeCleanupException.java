package io.pockethive.orchestrator.runtime;

import org.springframework.http.HttpStatus;

public class RuntimeCleanupException extends RuntimeException {
    private final HttpStatus status;

    public RuntimeCleanupException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }
}
