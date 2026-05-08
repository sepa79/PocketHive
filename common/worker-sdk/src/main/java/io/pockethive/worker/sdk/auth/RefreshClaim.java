package io.pockethive.worker.sdk.auth;

import java.time.Instant;

public record RefreshClaim(String tokenKey, String fingerprint, String ownerId, Instant leaseDeadline) {
}
