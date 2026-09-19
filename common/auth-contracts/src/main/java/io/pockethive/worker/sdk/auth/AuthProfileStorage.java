package io.pockethive.worker.sdk.auth;

/**
 * Responsibility: define the AuthProfileStorage contract.
 * Must not: select infrastructure clients or own adapter lifecycle.
 * Contract: RESP-AUTH-VALUES — docs/architecture/runtime-responsibilities.md#resp-auth-values.
 */
public final class AuthProfileStorage {
    private AuthStorageMode mode = AuthStorageMode.NONE;
    private String tokenKey;

    public AuthStorageMode getMode() {
        return mode;
    }

    public void setMode(AuthStorageMode mode) {
        this.mode = mode == null ? AuthStorageMode.NONE : mode;
    }

    public String getTokenKey() {
        return tokenKey;
    }

    public void setTokenKey(String tokenKey) {
        this.tokenKey = AuthProfile.normalize(tokenKey);
    }
}
