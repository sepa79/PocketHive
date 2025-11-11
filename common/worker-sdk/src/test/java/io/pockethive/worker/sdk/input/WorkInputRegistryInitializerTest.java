package io.pockethive.worker.sdk.input;

import static org.assertj.core.api.Assertions.assertThat;

import io.pockethive.worker.sdk.config.WorkInputConfig;
import io.pockethive.worker.sdk.config.WorkInputConfigBinder;
import io.pockethive.worker.sdk.config.WorkerCapability;
import io.pockethive.worker.sdk.config.WorkerInputType;
import io.pockethive.worker.sdk.config.WorkerOutputType;
import io.pockethive.worker.sdk.runtime.WorkIoBindings;
import io.pockethive.worker.sdk.runtime.WorkerDefinition;
import io.pockethive.worker.sdk.runtime.WorkerRegistry;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.core.Ordered;

class WorkInputRegistryInitializerTest {

    @Test
    void registersInputsViaFactories() {
        WorkerDefinition definition = new WorkerDefinition(
            "testWorker",
            Object.class,
            WorkerInputType.RABBITMQ,
            "test",
            WorkIoBindings.none(),
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

    @Test
    void prefersHigherPriorityFactory() {
        WorkerDefinition definition = new WorkerDefinition(
            "orderedWorker",
            Object.class,
            WorkerInputType.RABBITMQ,
            "test",
            WorkIoBindings.none(),
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
        WorkInput preferredInput = new WorkInput() {};
        WorkInputFactory preferred = new OrderedFactory(Ordered.HIGHEST_PRECEDENCE) {
            @Override
            public WorkInput create(WorkerDefinition def, WorkInputConfig config) {
                return preferredInput;
            }
        };
        WorkInputFactory fallback = new OrderedFactory(Ordered.LOWEST_PRECEDENCE);

        WorkInputRegistryInitializer initializer = new WorkInputRegistryInitializer(
            workerRegistry,
            registry,
            binder,
            List.of(fallback, preferred)
        );
        initializer.afterSingletonsInstantiated();

        assertThat(registry.find("orderedWorker"))
            .map(WorkInputRegistry.Registration::input)
            .contains(preferredInput);
    }

    private static class OrderedFactory implements WorkInputFactory, Ordered {
        private final int order;

        private OrderedFactory(int order) {
            this.order = order;
        }

        @Override
        public boolean supports(WorkerDefinition def) {
            return true;
        }

        @Override
        public WorkInput create(WorkerDefinition def, WorkInputConfig config) {
            return new WorkInput() {};
        }

        @Override
        public int getOrder() {
            return order;
        }
    }

    private static class WorkOutputConfigStub implements io.pockethive.worker.sdk.config.WorkOutputConfig {
    }
}
