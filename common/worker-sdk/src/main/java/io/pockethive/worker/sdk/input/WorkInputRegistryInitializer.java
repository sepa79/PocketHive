package io.pockethive.worker.sdk.input;

import io.pockethive.worker.sdk.config.WorkInputConfig;
import io.pockethive.worker.sdk.config.WorkInputConfigBinder;
import io.pockethive.worker.sdk.runtime.WorkerDefinition;
import io.pockethive.worker.sdk.runtime.WorkerRegistry;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;

/**
 * Populates the {@link WorkInputRegistry} using the available {@link WorkInputFactory} beans. If no
 * factory supports a worker definition we default to a noop input so that consumers can opt in
 * incrementally.
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
        if (factories == null || factories.isEmpty()) {
            this.factories = Collections.emptyList();
        } else {
            List<WorkInputFactory> sorted = new ArrayList<>(factories);
            AnnotationAwareOrderComparator.sort(sorted);
            this.factories = Collections.unmodifiableList(sorted);
        }
    }

    @Override
    public void afterSingletonsInstantiated() {
        workerRegistry.all().forEach(this::registerInput);
    }

    private void registerInput(WorkerDefinition definition) {
        WorkInput input = factories.stream()
            .filter(factory -> factory.supports(definition))
            .findFirst()
            .map(factory -> factory.create(definition, resolveConfig(definition)))
            .orElseThrow(() -> new IllegalStateException(
                "No WorkInputFactory found for worker '%s' (role=%s input=%s)".formatted(
                    definition.beanName(), definition.role(), definition.input())));
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
        return configBinder.bind(definition.role(), definition.inputConfigType());
    }
}
