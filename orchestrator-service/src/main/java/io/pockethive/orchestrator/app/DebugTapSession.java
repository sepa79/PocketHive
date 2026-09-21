package io.pockethive.orchestrator.app;

import io.pockethive.orchestrator.app.DebugTapController.DebugTapResponse;
import io.pockethive.orchestrator.app.DebugTapController.DebugTapSample;
import io.pockethive.topology.work.WorkDebugTap;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Responsibility: retain bounded samples and request lifetime for a single Work capture.
 * Must not: create broker resources, resolve source addresses or mutate swarm state.
 * Contract: RESP-WORK-RESOURCE-NAMES — docs/architecture/runtime-responsibilities.md#resp-work-resource-names.
 */
final class DebugTapSession {
    private final String id;
    private final String swarmId;
    private final String role;
    private final DebugTapDirection direction;
    private final String ioName;
    private final WorkDebugTap transport;
    private final int maxItems;
    private final int ttlSeconds;
    private final Instant createdAt;
    private final Instant expiresAt;
    private final Deque<DebugTapSample> samples = new ArrayDeque<>();
    private final Object lock = new Object();
    private volatile Instant lastReadAt;

    DebugTapSession(String id, String swarmId, String role, DebugTapDirection direction,
                    String ioName, WorkDebugTap transport, int maxItems, int ttlSeconds, Instant createdAt) {
        this.id = id;
        this.swarmId = swarmId;
        this.role = role;
        this.direction = direction;
        this.ioName = ioName;
        this.transport = Objects.requireNonNull(transport);
        this.maxItems = maxItems;
        this.ttlSeconds = ttlSeconds;
        this.createdAt = createdAt;
        this.expiresAt = createdAt.plus(Duration.ofSeconds(ttlSeconds));
        this.lastReadAt = createdAt;
    }

    WorkDebugTap transport() { return transport; }
    int maxItems() { return maxItems; }
    boolean isExpired(Instant now) { return now.isAfter(expiresAt); }

    void drain(int limit) {
        int drained = 0;
        while (drained < limit) {
            byte[] body = transport.receive().orElse(null);
            if (body == null) break;
            var sample = new DebugTapSample(UUID.randomUUID().toString(), Instant.now(), body.length,
                new String(body, StandardCharsets.UTF_8));
            synchronized (lock) {
                while (samples.size() >= maxItems) samples.pollFirst();
                samples.addLast(sample);
            }
            drained++;
        }
        lastReadAt = Instant.now();
    }

    DebugTapResponse snapshot() {
        List<DebugTapSample> snapshot;
        synchronized (lock) { snapshot = List.copyOf(samples); }
        return new DebugTapResponse(id, swarmId, role, direction.name(), ioName, transport.sourceGroup(),
            transport.sourceAddress(), transport.captureAddress(), maxItems, ttlSeconds, createdAt, lastReadAt, snapshot);
    }
}
