package io.pockethive.worker.sdk.runtime;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Keeps a registry of discovered workers keyed by bean name.
 */
public final class WorkerRegistry {

    private final Map<String, WorkerDefinition> workers;

    public WorkerRegistry(Collection<WorkerDefinition> definitions) {
        Map<String, WorkerDefinition> copy = new LinkedHashMap<>();
        for (WorkerDefinition definition : definitions) {
            copy.put(definition.beanName(), definition);
        }
        this.workers = Collections.unmodifiableMap(copy);
    }

    public Collection<WorkerDefinition> all() {
        return workers.values();
    }

    public Optional<WorkerDefinition> find(String beanName) {
        return Optional.ofNullable(workers.get(beanName));
    }
}
