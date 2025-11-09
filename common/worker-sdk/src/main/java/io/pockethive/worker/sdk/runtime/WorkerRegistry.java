package io.pockethive.worker.sdk.runtime;

import io.pockethive.worker.sdk.config.WorkerInputType;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

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

    /**
     * Returns a stream of worker definitions registered for the given role.
     */
    public Stream<WorkerDefinition> streamByRole(String role) {
        String resolvedRole = requireText(role, "role");
        return workers.values().stream().filter(definition -> resolvedRole.equals(definition.role()));
    }

    /**
     * Returns a stream of worker definitions matching the supplied role and input binding.
     */
    public Stream<WorkerDefinition> streamByRoleAndInput(String role, WorkerInputType input) {
        Objects.requireNonNull(input, "input");
        return streamByRole(role).filter(definition -> definition.input() == input);
    }

    /**
     * Finds the first worker definition matching the supplied role and input binding.
     */
    public Optional<WorkerDefinition> findByRoleAndInput(String role, WorkerInputType input) {
        return streamByRoleAndInput(role, input).findFirst();
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
