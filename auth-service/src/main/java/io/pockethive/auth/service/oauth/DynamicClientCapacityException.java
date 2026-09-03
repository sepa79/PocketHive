package io.pockethive.auth.service.oauth;

/**
 * Responsibility: Represent an explicit dynamic-client capacity refusal.
 * Must not: Bypass canonical scope policy, client authentication, or Spring Authorization Server contracts.
 * Contract: docs/architecture/AUTH_SERVICE_API_SPEC.md and docs/AUTH-BEHAVIOR.md.
 */

final class DynamicClientCapacityException extends RuntimeException {
    DynamicClientCapacityException() {
        super("Dynamic client registration capacity is exhausted");
    }
}
