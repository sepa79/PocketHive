package io.pockethive.worker.sdk.auth;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Thread-safe first-occurrence gate for auth failures that are surfaced through worker journals.
 */
public final class AuthFailureJournalDeduplicator {

    private final ConcurrentMap<String, AtomicInteger> counts = new ConcurrentHashMap<>();

    public Decision record(String scope, AuthFailureException failure) {
        Objects.requireNonNull(failure, "failure");
        String normalizedScope = scope == null || scope.isBlank() ? "default" : scope.trim();
        AtomicInteger count = counts.computeIfAbsent(
            normalizedScope + ":" + failure.dedupeKey(),
            ignored -> new AtomicInteger()
        );
        int occurrences = count.incrementAndGet();
        return new Decision(occurrences == 1, occurrences);
    }

    public record Decision(boolean firstOccurrence, int occurrences) {
    }
}
