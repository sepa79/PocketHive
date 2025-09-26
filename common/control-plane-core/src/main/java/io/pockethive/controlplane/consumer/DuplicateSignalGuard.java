package io.pockethive.controlplane.consumer;

import io.pockethive.control.ControlSignal;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Tracks recently-processed signals using their idempotency/correlation identifiers.
 */
public final class DuplicateSignalGuard {

    private final Duration ttl;
    private final int maxEntries;
    private final Clock clock;
    private final boolean enabled;
    private final LinkedHashMap<String, Instant> seen;

    private DuplicateSignalGuard(Duration ttl, int maxEntries, Clock clock, boolean enabled) {
        this.ttl = ttl;
        this.maxEntries = maxEntries;
        this.clock = clock;
        this.enabled = enabled;
        this.seen = new LinkedHashMap<>(64, 0.75f, true);
    }

    public static DuplicateSignalGuard disabled() {
        return new DuplicateSignalGuard(Duration.ZERO, 0, Clock.systemUTC(), false);
    }

    public static DuplicateSignalGuard create(Duration ttl, int maxEntries) {
        return create(ttl, maxEntries, Clock.systemUTC());
    }

    public static DuplicateSignalGuard create(Duration ttl, int maxEntries, Clock clock) {
        Objects.requireNonNull(ttl, "ttl");
        Objects.requireNonNull(clock, "clock");
        if (ttl.isNegative()) {
            throw new IllegalArgumentException("ttl must be >= 0");
        }
        if (maxEntries < 0) {
            throw new IllegalArgumentException("maxEntries must be >= 0");
        }
        if (ttl.isZero() || maxEntries == 0) {
            return disabled();
        }
        return new DuplicateSignalGuard(ttl, maxEntries, clock, true);
    }

    public synchronized boolean markIfNew(ControlSignal signal) {
        if (!enabled) {
            return true;
        }
        String key = dedupKey(signal);
        if (key == null) {
            return true;
        }
        Instant now = clock.instant();
        Instant previous = seen.put(key, now);
        prune(now);
        return previous == null || Duration.between(previous, now).compareTo(ttl) > 0;
    }

    private void prune(Instant now) {
        Iterator<Map.Entry<String, Instant>> iterator = seen.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, Instant> entry = iterator.next();
            if (Duration.between(entry.getValue(), now).compareTo(ttl) > 0) {
                iterator.remove();
            } else {
                break;
            }
        }
        while (seen.size() > maxEntries) {
            Iterator<String> keys = seen.keySet().iterator();
            if (keys.hasNext()) {
                keys.next();
                keys.remove();
            } else {
                break;
            }
        }
    }

    private String dedupKey(ControlSignal signal) {
        if (signal == null) {
            return null;
        }
        if (signal.idempotencyKey() != null && !signal.idempotencyKey().isBlank()) {
            return signal.idempotencyKey();
        }
        if (signal.correlationId() != null && !signal.correlationId().isBlank()) {
            return signal.correlationId();
        }
        return null;
    }
}
