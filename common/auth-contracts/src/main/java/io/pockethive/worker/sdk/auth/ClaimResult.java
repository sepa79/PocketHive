package io.pockethive.worker.sdk.auth;

/**
 * Responsibility: define the ClaimResult contract.
 * Must not: configure transport clients or own adapter lifecycle.
 * Contract: RESP-AUTH-TOKEN-STORE — docs/architecture/runtime-responsibilities.md#resp-auth-token-store.
 */
public enum ClaimResult {
    CLAIMED,
    OWNED_BY_OTHER,
    FINGERPRINT_MISMATCH
}
