package io.pockethive.worker.sdk.auth;

import java.util.Map;

/**
 * Responsibility: define the AuthProfileDocument contract.
 * Must not: configure transport clients or own adapter lifecycle.
 * Contract: RESP-AUTH-VALUES — docs/architecture/runtime-responsibilities.md#resp-auth-values.
 */
public record AuthProfileDocument(Map<String, AuthProfile> profiles) {
    public AuthProfileDocument {
        profiles = profiles == null ? Map.of() : Map.copyOf(profiles);
    }
}
