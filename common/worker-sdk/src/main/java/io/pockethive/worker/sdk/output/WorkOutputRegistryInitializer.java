package io.pockethive.worker.sdk.output;

import io.pockethive.worker.sdk.config.WorkOutputConfig;
import io.pockethive.worker.sdk.config.WorkOutputConfigBinder;
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

public final class WorkOutputRegistryInitializer implements SmartInitializingSingleton {

    private static final Logger log = LoggerFactory.getLogger(WorkOutputRegistryInitializer.class);
    private final WorkerRegistry workerRegistry;
    private final WorkOutputRegistry outputRegistry;
    private final WorkOutputConfigBinder configBinder;
    private final List<WorkOutputFactory> factories;

    public WorkOutputRegistryInitializer(
        WorkerRegistry workerRegistry,
        WorkOutputRegistry outputRegistry,
        WorkOutputConfigBinder configBinder,
        List<WorkOutputFactory> factories
    ) {
        this.workerRegistry = Objects.requireNonNull(workerRegistry, "workerRegistry");
        this.outputRegistry = Objects.requireNonNull(outputRegistry, "outputRegistry");
        this.configBinder = Objects.requireNonNull(configBinder, "configBinder");
        if (factories == null || factories.isEmpty()) {
            this.factories = Collections.emptyList();
        } else {
            List<WorkOutputFactory> sorted = new ArrayList<>(factories);
            AnnotationAwareOrderComparator.sort(sorted);
            this.factories = Collections.unmodifiableList(sorted);
        }
    }

    @Override
    public void afterSingletonsInstantiated() {
        workerRegistry.all().forEach(this::registerOutput);
    }

    private void registerOutput(WorkerDefinition definition) {
        WorkOutput output = factories.stream()
            .filter(factory -> factory.supports(definition))
            .findFirst()
            .map(factory -> {
                WorkOutputConfig config = configBinder.bind(definition.outputType(), definition.outputConfigType());
                return factory.create(definition, config);
            })
            .orElseGet(NoopWorkOutput::new);
        outputRegistry.register(definition, output);
        if (log.isInfoEnabled()) {
            String outputName = output.getClass().getSimpleName();
            if (outputName == null || outputName.isBlank()) {
                outputName = output.getClass().getName();
            }
            log.info("Registered {} work output for worker {}", outputName, definition.beanName());
        }
    }
}
