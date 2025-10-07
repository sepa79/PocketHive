package io.pockethive.worker.sdk.runtime;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Keeps a registry of discovered workers keyed by bean name.
 * The Stage 1 runtime uses the registry to resolve invocations (see {@code docs/sdk/worker-sdk-quickstart.md}).
 */
public final class WorkerRegistry {

    private final Map<String, WorkerDefinition> workers;

    /**
     * Creates a registry from the supplied worker definitions.
     */
    public WorkerRegistry(Collection<WorkerDefinition> definitions) {
        Map<String, WorkerDefinition> copy = new LinkedHashMap<>();
        for (WorkerDefinition definition : definitions) {
            copy.put(definition.beanName(), definition);
        }
        this.workers = Collections.unmodifiableMap(copy);
    }

    /**
     * Returns all registered worker definitions.
     */
    public Collection<WorkerDefinition> all() {
        return workers.values();
    }

    /**
     * Looks up a worker definition by bean name.
     */
    public Optional<WorkerDefinition> find(String beanName) {
        return Optional.ofNullable(workers.get(beanName));
    }
}
