package io.pockethive.auth.service.oauth;

final class DynamicClientCapacityException extends RuntimeException {
    DynamicClientCapacityException() {
        super("Dynamic client registration capacity is exhausted");
    }
}
