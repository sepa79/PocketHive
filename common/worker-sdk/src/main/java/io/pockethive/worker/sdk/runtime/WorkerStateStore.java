package io.pockethive.worker.sdk.runtime;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Repository of {@link WorkerState} instances keyed by worker bean name.
 */
public final class WorkerStateStore {

    private final Map<String, WorkerState> states = new ConcurrentHashMap<>();

    public WorkerState getOrCreate(WorkerDefinition definition) {
        Objects.requireNonNull(definition, "definition");
        return states.computeIfAbsent(definition.beanName(), key -> new WorkerState(definition));
    }

    Optional<WorkerState> find(String beanName) {
        return Optional.ofNullable(states.get(beanName));
    }

    Collection<WorkerState> all() {
        return Collections.unmodifiableCollection(states.values());
    }
}
