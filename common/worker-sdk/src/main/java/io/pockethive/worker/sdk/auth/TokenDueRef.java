package io.pockethive.worker.sdk.auth;

import java.time.Instant;

public record TokenDueRef(String tokenKey, String fingerprint, Instant refreshAt) {
}
