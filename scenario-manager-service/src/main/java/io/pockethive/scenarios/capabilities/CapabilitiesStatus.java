package io.pockethive.scenarios.capabilities;

import java.time.Duration;
import java.time.Instant;

public record CapabilitiesStatus(
        Instant lastFetchAttempt,
        Instant lastSuccessfulFetch,
        Duration cacheTtl,
        boolean stale,
        int runtimeSwarmCount,
        String lastFailureMessage,
        OfflineStatus offline) {
}
