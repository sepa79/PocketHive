package io.pockethive.worker.sdk.auth;

import java.time.Instant;

/**
 * Responsibility: define the TokenDueRef contract.
 * Must not: configure transport clients or own adapter lifecycle.
 * Contract: RESP-AUTH-TOKEN-STORE — docs/architecture/runtime-responsibilities.md#resp-auth-token-store.
 */
public record TokenDueRef(String tokenKey, String fingerprint, Instant refreshAt) {
}
