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
        if (definition.inQueue() != null) {
            workInRoutes.add(definition.inQueue());
        }
        if (definition.outQueue() != null) {
            workOutRoutes.add(definition.outQueue());
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
        configRef.set(config);
        rawConfigRef.set(rawData == null ? Map.of() : Map.copyOf(rawData));
        enabledRef.set(enabled);
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
        if (queue != null && !queue.isBlank()) {
            workInRoutes.add(queue);
        }
    }

    void addOutboundRoute(String queue) {
        if (queue != null && !queue.isBlank()) {
            workOutRoutes.add(queue);
        }
    }

    Set<String> inboundRoutes() {
        return Set.copyOf(workInRoutes);
    }

    Set<String> outboundRoutes() {
        return Set.copyOf(workOutRoutes);
    }
}
