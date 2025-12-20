package io.pockethive.orchestrator.app;

import io.pockethive.orchestrator.domain.SwarmRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class ControlPlaneSyncService {

    private static final Duration MIN_INTERVAL = Duration.ofSeconds(2);

    private final SwarmRegistry registry;
    private final SwarmSignalListener orchestratorSignals;
    private final ControlPlaneStatusRequestPublisher publisher;
    private final Clock clock;
    private final AtomicLong lastIssuedMs = new AtomicLong(0L);

    @Autowired
    public ControlPlaneSyncService(SwarmRegistry registry,
                                   SwarmSignalListener orchestratorSignals,
                                   ControlPlaneStatusRequestPublisher publisher) {
        this(registry, orchestratorSignals, publisher, Clock.systemUTC());
    }

    ControlPlaneSyncService(SwarmRegistry registry,
                            SwarmSignalListener orchestratorSignals,
                            ControlPlaneStatusRequestPublisher publisher,
                            Clock clock) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.orchestratorSignals = Objects.requireNonNull(orchestratorSignals, "orchestratorSignals");
        this.publisher = Objects.requireNonNull(publisher, "publisher");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public ControlPlaneSyncResponse refresh() {
        return sync(SyncMode.REFRESH);
    }

    public ControlPlaneSyncResponse reset() {
        return sync(SyncMode.RESET);
    }

    private ControlPlaneSyncResponse sync(SyncMode mode) {
        Objects.requireNonNull(mode, "mode");
        Instant now = clock.instant();
        long nowMs = now.toEpochMilli();
        long lastMs = lastIssuedMs.get();
        if (lastMs > 0 && Duration.ofMillis(nowMs - lastMs).compareTo(MIN_INTERVAL) < 0) {
            return new ControlPlaneSyncResponse(mode, null, null, 0, true, now);
        }
        lastIssuedMs.set(nowMs);

        if (mode == SyncMode.RESET) {
            registry.clear();
        }

        orchestratorSignals.requestStatusFull();

        String correlationId = UUID.randomUUID().toString();
        String idempotencyKey = "status-request:" + UUID.randomUUID();
        int signals = 0;

        List<String> swarmIds = registry.all().stream().map(s -> s.getId()).toList();
        if (swarmIds.isEmpty()) {
            publisher.requestStatusForAllControllers(correlationId, idempotencyKey);
            signals++;
        } else {
            for (String swarmId : swarmIds) {
                publisher.requestStatusForSwarm(swarmId, correlationId, idempotencyKey);
                signals++;
            }
        }

        return new ControlPlaneSyncResponse(mode, correlationId, idempotencyKey, signals, false, now);
    }

    public enum SyncMode {
        REFRESH,
        RESET
    }
}
