package io.pockethive.worker.sdk.input;

import static org.assertj.core.api.Assertions.assertThat;

import io.pockethive.worker.sdk.config.WorkInputConfig;
import io.pockethive.worker.sdk.config.WorkInputConfigBinder;
import io.pockethive.worker.sdk.config.WorkerCapability;
import io.pockethive.worker.sdk.config.WorkerInputType;
import io.pockethive.worker.sdk.config.WorkerOutputType;
import io.pockethive.worker.sdk.runtime.WorkerDefinition;
import io.pockethive.worker.sdk.runtime.WorkerRegistry;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

class WorkInputRegistryInitializerTest {

    @Test
    void registersInputsViaFactories() {
        WorkerDefinition definition = new WorkerDefinition(
            "testWorker",
            Object.class,
            WorkerInputType.RABBIT,
            "test",
            null,
            null,
            null,
            Void.class,
            WorkInputConfig.class,
            WorkOutputConfigStub.class,
            WorkerOutputType.NONE,
            "Test worker",
            Set.of(WorkerCapability.MESSAGE_DRIVEN)
        );
        WorkerRegistry workerRegistry = new WorkerRegistry(List.of(definition));
        WorkInputRegistry registry = new WorkInputRegistry();
        WorkInputConfigBinder binder = new WorkInputConfigBinder(new Binder(new MapConfigurationPropertySource()));
        WorkInputFactory factory = new WorkInputFactory() {
            @Override
            public boolean supports(WorkerDefinition def) {
                return true;
            }

            @Override
            public WorkInput create(WorkerDefinition def, WorkInputConfig config) {
                return new WorkInput() {};
            }
        };

        WorkInputRegistryInitializer initializer = new WorkInputRegistryInitializer(
            workerRegistry,
            registry,
            binder,
            List.of(factory)
        );
        initializer.afterSingletonsInstantiated();

        assertThat(registry.find("testWorker")).isPresent();
    }

    private static class WorkOutputConfigStub implements io.pockethive.worker.sdk.config.WorkOutputConfig {
    }
}
