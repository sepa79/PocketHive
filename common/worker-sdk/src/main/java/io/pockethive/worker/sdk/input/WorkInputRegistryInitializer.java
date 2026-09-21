package io.pockethive.worker.sdk.input;

import io.pockethive.work.config.binding.WorkInputConfig;
import io.pockethive.worker.sdk.config.WorkInputConfigBinder;
import io.pockethive.worker.sdk.runtime.WorkerDefinition;
import io.pockethive.worker.sdk.runtime.WorkerRegistry;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;

/**
 * Populates the {@link WorkInputRegistry} using the available {@link WorkInputFactory} beans and
 * fails fast when no factory supports a worker definition.
 * <p>
 * Responsibility: register exactly one matching input adapter for each worker.
 * Must not: resolve ambiguity by ordering or hide missing adapters.
 * Contract: RESP-WORK-ADAPTER-SELECTION — docs/architecture/runtime-responsibilities.md#resp-work-adapter-selection.
 */
public final class WorkInputRegistryInitializer implements SmartInitializingSingleton {

    private static final Logger log = LoggerFactory.getLogger(WorkInputRegistryInitializer.class);

    private final WorkerRegistry workerRegistry;
    private final WorkInputRegistry registry;
    private final WorkInputConfigBinder configBinder;
    private final List<WorkInputFactory> factories;

    public WorkInputRegistryInitializer(
        WorkerRegistry workerRegistry,
        WorkInputRegistry registry,
        WorkInputConfigBinder configBinder,
        List<WorkInputFactory> factories
    ) {
        this.workerRegistry = Objects.requireNonNull(workerRegistry, "workerRegistry");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.configBinder = Objects.requireNonNull(configBinder, "configBinder");
        this.factories = List.copyOf(Objects.requireNonNull(factories, "factories"));
    }

    @Override
    public void afterSingletonsInstantiated() {
        workerRegistry.all().forEach(this::registerInput);
    }

    private void registerInput(WorkerDefinition definition) {
        List<WorkInputFactory> matches = factories.stream()
            .filter(factory -> factory.supports(definition)).toList();
        if (matches.isEmpty()) {
            throw new IllegalStateException("No WorkInputFactory found for worker '" + definition.beanName() + "'");
        }
        if (matches.size() != 1) {
            throw new IllegalStateException("Multiple WorkInputFactory matches for worker " + definition.beanName()
                + ": " + matches.stream().map(factory -> factory.getClass().getName()).toList());
        }
        WorkInput input = matches.getFirst().create(definition, resolveConfig(definition));
        registry.register(definition, input);
        if (log.isInfoEnabled()) {
            String inputName = input.getClass().getSimpleName();
            if (inputName == null || inputName.isBlank()) {
                inputName = input.getClass().getName();
            }
            log.info("Registered {} work input for worker {}", inputName, definition.beanName());
        }
    }

    private WorkInputConfig resolveConfig(WorkerDefinition definition) {
        return configBinder.bind(definition.input(), definition.inputConfigType());
    }
}
