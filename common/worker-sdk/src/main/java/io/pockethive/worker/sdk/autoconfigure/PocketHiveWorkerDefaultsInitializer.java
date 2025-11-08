package io.pockethive.worker.sdk.autoconfigure;

import io.pockethive.worker.sdk.config.PocketHiveWorkerProperties;
import io.pockethive.worker.sdk.runtime.WorkerControlPlaneRuntime;
import io.pockethive.worker.sdk.runtime.WorkerDefinition;
import io.pockethive.worker.sdk.runtime.WorkerRegistry;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;

final class PocketHiveWorkerDefaultsInitializer implements SmartInitializingSingleton {

    private static final Logger log = LoggerFactory.getLogger(PocketHiveWorkerDefaultsInitializer.class);

    private final WorkerRegistry workerRegistry;
    private final WorkerControlPlaneRuntime controlPlaneRuntime;
    private final List<PocketHiveWorkerProperties<?>> properties;

    PocketHiveWorkerDefaultsInitializer(
        WorkerRegistry workerRegistry,
        WorkerControlPlaneRuntime controlPlaneRuntime,
        List<PocketHiveWorkerProperties<?>> properties
    ) {
        this.workerRegistry = Objects.requireNonNull(workerRegistry, "workerRegistry");
        this.controlPlaneRuntime = Objects.requireNonNull(controlPlaneRuntime, "controlPlaneRuntime");
        this.properties = properties == null ? List.of() : List.copyOf(properties);
    }

    @Override
    public void afterSingletonsInstantiated() {
        if (properties.isEmpty()) {
            return;
        }
        for (PocketHiveWorkerProperties<?> props : properties) {
            registerDefaults(props);
        }
    }

    private void registerDefaults(PocketHiveWorkerProperties<?> props) {
        if (!props.hasConfigOverrides()) {
            log.debug("No worker defaults configured for role '{}'", props.role());
            return;
        }
        Map<String, Object> rawConfig = props.rawConfig();
        List<WorkerDefinition> definitions = workerRegistry.streamByRole(props.role()).collect(Collectors.toList());
        if (definitions.isEmpty()) {
            log.warn("Worker defaults defined for role '{}' but no workers were registered with that role", props.role());
            return;
        }
        for (WorkerDefinition definition : definitions) {
            controlPlaneRuntime.registerDefaultConfig(definition.beanName(), rawConfig);
        }
        log.info("Registered default config for role '{}' across {} worker(s)", props.role(), definitions.size());
    }
}
