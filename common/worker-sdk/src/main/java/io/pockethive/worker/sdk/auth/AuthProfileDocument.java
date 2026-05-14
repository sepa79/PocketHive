package io.pockethive.worker.sdk.auth;

import java.util.Map;

public record AuthProfileDocument(Map<String, AuthProfile> profiles) {
    public AuthProfileDocument {
        profiles = profiles == null ? Map.of() : Map.copyOf(profiles);
    }
}
