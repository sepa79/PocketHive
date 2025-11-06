package io.pockethive.worker.sdk.input;

import io.pockethive.worker.sdk.runtime.WorkerDefinition;
import io.pockethive.worker.sdk.runtime.WorkerRegistry;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Populates the {@link WorkInputRegistry} with default inputs for the workers discovered in the
 * {@link WorkerRegistry}. Currently registers no-op placeholders that will be replaced by concrete
 * inputs as the refactor progresses.
 */
public final class WorkInputRegistryInitializer {

    private static final Logger log = LoggerFactory.getLogger(WorkInputRegistryInitializer.class);

    public WorkInputRegistryInitializer(WorkerRegistry workerRegistry, WorkInputRegistry registry) {
        Objects.requireNonNull(workerRegistry, "workerRegistry");
        Objects.requireNonNull(registry, "registry");
        for (WorkerDefinition definition : workerRegistry.all()) {
            registry.register(definition, WorkInputs.noop());
            log.debug("Registered noop work input for worker {}", definition.beanName());
        }
    }
}
