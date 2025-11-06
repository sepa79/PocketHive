package io.pockethive.worker.sdk.input;

import io.pockethive.worker.sdk.runtime.WorkerDefinition;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry that keeps track of {@link WorkInput} instances bound to worker definitions.
 */
public final class WorkInputRegistry {

    private final Map<String, Registration> registrations = new ConcurrentHashMap<>();

    /**
     * Registers a {@link WorkInput} for the given worker definition, replacing any previous entry.
     *
     * @return the registration created for the worker
     */
    public Registration register(WorkerDefinition definition, WorkInput input) {
        Objects.requireNonNull(definition, "definition");
        Objects.requireNonNull(input, "input");
        Registration registration = new Registration(definition, input);
        registrations.put(definition.beanName(), registration);
        return registration;
    }

    /**
     * Removes and returns the registration associated with the worker bean name.
     */
    public Optional<Registration> unregister(String beanName) {
        if (beanName == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(registrations.remove(beanName));
    }

    /**
     * Returns an immutable snapshot of the current registrations.
     */
    public Collection<Registration> registrations() {
        return Collections.unmodifiableCollection(registrations.values());
    }

    /**
     * Retrieves the registration for the given worker bean name, if present.
     */
    public Optional<Registration> find(String beanName) {
        if (beanName == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(registrations.get(beanName));
    }

    /**
     * Value object pairing a worker definition with its input.
     */
    public record Registration(WorkerDefinition definition, WorkInput input) {
    }
}
