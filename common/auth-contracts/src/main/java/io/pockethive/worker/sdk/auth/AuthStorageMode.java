package io.pockethive.worker.sdk.auth;

/**
 * Responsibility: define the AuthStorageMode contract.
 * Must not: configure transport clients or own adapter lifecycle.
 * Contract: RESP-AUTH-VALUES — docs/architecture/runtime-responsibilities.md#resp-auth-values.
 */
public enum AuthStorageMode {
    REDIS,
    NONE
}
