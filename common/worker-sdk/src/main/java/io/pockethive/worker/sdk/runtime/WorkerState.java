package io.pockethive.worker.sdk.runtime;

import io.pockethive.worker.sdk.api.StatusPublisher;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;

/**
 * Tracks per-worker state for the runtime. Exposed indirectly via
 * {@link WorkerControlPlaneRuntime.WorkerStateSnapshot}.
 */
public final class WorkerState {

    private final WorkerDefinition definition;
    private final AtomicReference<Object> configRef = new AtomicReference<>();
    private final AtomicReference<Boolean> enabledRef = new AtomicReference<>(Boolean.TRUE);
    private final AtomicReference<StatusPublisher> statusPublisherRef = new AtomicReference<>(StatusPublisher.NO_OP);
    private final AtomicReference<Map<String, Object>> statusDataRef = new AtomicReference<>(Map.of());
    private final LongAdder processedMessages = new LongAdder();
    private final Set<String> workInRoutes = ConcurrentHashMap.newKeySet();
    private final Set<String> workOutRoutes = ConcurrentHashMap.newKeySet();

    WorkerState(WorkerDefinition definition) {
        this.definition = Objects.requireNonNull(definition, "definition");
        WorkIoBindings io = definition.io();
        addIfPresent(workInRoutes, io.inboundQueue());
        addIfPresent(workOutRoutes, io.outboundQueue());
    }

    WorkerDefinition definition() {
        return definition;
    }

    void setStatusPublisher(StatusPublisher publisher) {
        statusPublisherRef.set(Objects.requireNonNull(publisher, "publisher"));
    }

    StatusPublisher statusPublisher() {
        return statusPublisherRef.get();
    }

    void mutateStatusData(Function<Map<String, Object>, Map<String, Object>> mutator) {
        Objects.requireNonNull(mutator, "mutator");
        statusDataRef.updateAndGet(current -> {
            Map<String, Object> working = new LinkedHashMap<>(current);
            Map<String, Object> result = mutator.apply(working);
            Map<String, Object> target = result == null ? working : result;
            if (target.isEmpty()) {
                return Map.of();
            }
            return Map.copyOf(target);
        });
    }

    Map<String, Object> statusData() {
        return statusDataRef.get();
    }

    void updateConfig(Object config, boolean replaceConfig, Boolean enabled) {
        if (replaceConfig) {
            if (config != null) {
                configRef.set(config);
            } else {
                configRef.set(null);
            }
        }
        if (enabled != null) {
            enabledRef.set(enabled);
        }
    }

    boolean seedConfig(Object config, Boolean enabled) {
        synchronized (this) {
            if (configRef.get() != null || enabledRef.get() != null) {
                return false;
            }
            if (config != null) {
                configRef.set(config);
            }
            if (enabled != null) {
                enabledRef.set(enabled);
            }
            return true;
        }
    }

    boolean enabled() {
        Boolean value = enabledRef.get();
        return value == null ? true : value;
    }

    <C> Optional<C> config(Class<C> type) {
        Objects.requireNonNull(type, "type");
        Object value = configRef.get();
        if (value != null && type.isInstance(value)) {
            return Optional.of(type.cast(value));
        }
        return Optional.empty();
    }

    void recordWork() {
        processedMessages.increment();
    }

    long drainProcessedCount() {
        return processedMessages.sumThenReset();
    }

    long peekProcessedCount() {
        return processedMessages.sum();
    }

    void addInboundRoute(String queue) {
        addIfPresent(workInRoutes, queue);
    }

    void addOutboundRoute(String queue) {
        addIfPresent(workOutRoutes, queue);
    }

    Set<String> inboundRoutes() {
        return Set.copyOf(workInRoutes);
    }

    Set<String> outboundRoutes() {
        return Set.copyOf(workOutRoutes);
    }

    private static void addIfPresent(Set<String> target, String queue) {
        String normalised = normalise(queue);
        if (normalised != null) {
            target.add(normalised);
        }
    }

    private static String normalise(String queue) {
        if (queue == null) {
            return null;
        }
        String trimmed = queue.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
