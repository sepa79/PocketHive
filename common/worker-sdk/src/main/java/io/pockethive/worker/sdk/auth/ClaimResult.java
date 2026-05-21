package io.pockethive.worker.sdk.auth;

public enum ClaimResult {
    CLAIMED,
    OWNED_BY_OTHER,
    FINGERPRINT_MISMATCH,
    STORE_UNAVAILABLE
}
