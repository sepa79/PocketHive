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
    private final AtomicReference<Map<String, Object>> rawConfigRef = new AtomicReference<>(Map.of());
    private final AtomicReference<Boolean> enabledRef = new AtomicReference<>();
    private final AtomicReference<StatusPublisher> statusPublisherRef = new AtomicReference<>(StatusPublisher.NO_OP);
    private final AtomicReference<Map<String, Object>> statusDataRef = new AtomicReference<>(Map.of());
    private final LongAdder processedMessages = new LongAdder();
    private final Set<String> workInRoutes = ConcurrentHashMap.newKeySet();
    private final Set<String> workOutRoutes = ConcurrentHashMap.newKeySet();

    WorkerState(WorkerDefinition definition) {
        this.definition = Objects.requireNonNull(definition, "definition");
        String inbound = definition.resolvedInQueue();
        if (inbound != null) {
            workInRoutes.add(inbound);
        }
        String outbound = definition.resolvedOutQueue();
        if (outbound != null) {
            workOutRoutes.add(outbound);
        }
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

    void updateConfig(Object config, Map<String, Object> rawData, Boolean enabled) {
        if (rawData != null) {
            Map<String, Object> copy = rawData.isEmpty() ? Map.of() : Map.copyOf(rawData);
            rawConfigRef.set(copy);
            if (copy.isEmpty()) {
                configRef.set(null);
            } else if (config != null) {
                configRef.set(config);
            }
        } else if (config != null) {
            configRef.set(config);
        }
        if (enabled != null) {
            enabledRef.set(enabled);
        }
    }

    boolean seedConfig(Object config, Map<String, Object> rawData, Boolean enabled) {
        synchronized (this) {
            if (configRef.get() != null || !rawConfigRef.get().isEmpty() || enabledRef.get() != null) {
                return false;
            }
            if (config != null) {
                configRef.set(config);
            }
            if (rawData != null && !rawData.isEmpty()) {
                rawConfigRef.set(Map.copyOf(rawData));
            }
            if (enabled != null) {
                enabledRef.set(enabled);
            }
            return true;
        }
    }

    Map<String, Object> rawConfig() {
        return rawConfigRef.get();
    }

    Optional<Boolean> enabled() {
        return Optional.ofNullable(enabledRef.get());
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
        String resolved = WorkerDefinition.resolveQueue(queue);
        if (resolved != null) {
            workInRoutes.add(resolved);
        }
    }

    void addOutboundRoute(String queue) {
        String resolved = WorkerDefinition.resolveQueue(queue);
        if (resolved != null) {
            workOutRoutes.add(resolved);
        }
    }

    Set<String> inboundRoutes() {
        return Set.copyOf(workInRoutes);
    }

    Set<String> outboundRoutes() {
        return Set.copyOf(workOutRoutes);
    }

}
