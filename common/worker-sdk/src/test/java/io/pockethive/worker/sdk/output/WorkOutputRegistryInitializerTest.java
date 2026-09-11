package io.pockethive.worker.sdk.output;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.pockethive.worker.sdk.config.RabbitOutputProperties;
import io.pockethive.worker.sdk.config.WorkInputConfig;
import io.pockethive.worker.sdk.config.WorkOutputConfig;
import io.pockethive.worker.sdk.config.WorkOutputConfigBinder;
import io.pockethive.work.api.WorkerCapability;
import io.pockethive.work.config.WorkerInputType;
import io.pockethive.work.config.WorkerOutputType;
import io.pockethive.worker.sdk.runtime.WorkIoBindings;
import io.pockethive.worker.sdk.runtime.WorkerDefinition;
import io.pockethive.worker.sdk.runtime.WorkerRegistry;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import io.pockethive.rabbit.api.RabbitPublisher;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.core.Ordered;

class WorkOutputRegistryInitializerTest {

    @Test
    void registersOutputsBasedOnDefinition() {
        WorkerDefinition noopDefinition = new WorkerDefinition(
            "noopWorker",
            Object.class,
            WorkerInputType.SCHEDULER,
            "noop",
            WorkIoBindings.none(),
            Void.class,
            WorkInputConfig.class,
            WorkOutputConfig.class,
            WorkerOutputType.NONE,
            "",
            Set.of()
        );
        WorkerDefinition rabbitDefinition = new WorkerDefinition(
            "rabbitWorker",
            Object.class,
            WorkerInputType.RABBITMQ,
            "processor",
            WorkIoBindings.of(null, "processor.out", "exchange"),
            Void.class,
            WorkInputConfig.class,
            RabbitOutputProperties.class,
            WorkerOutputType.RABBITMQ,
            "",
            Set.of(WorkerCapability.MESSAGE_DRIVEN)
        );
        WorkerRegistry workerRegistry = new WorkerRegistry(List.of(noopDefinition, rabbitDefinition));
        WorkOutputRegistry outputRegistry = new WorkOutputRegistry();
        MapConfigurationPropertySource source = new MapConfigurationPropertySource(Map.of(
            "pockethive.outputs.rabbit.routing-key", "custom.out",
            "pockethive.outputs.rabbit.exchange", "exchange"
        ));
        WorkOutputConfigBinder binder = new WorkOutputConfigBinder(new Binder(source));
        RabbitPublisher rabbitTemplate = org.mockito.Mockito.mock(RabbitPublisher.class);
        List<WorkOutputFactory> factories = List.of(
            new NoopWorkOutputFactory(),
            new RabbitWorkOutputFactory(rabbitTemplate)
        );

        WorkOutputRegistryInitializer initializer = new WorkOutputRegistryInitializer(
            workerRegistry,
            outputRegistry,
            binder,
            factories
        );
        initializer.afterSingletonsInstantiated();

        assertThat(outputRegistry.get("noopWorker")).isInstanceOf(NoopWorkOutput.class);
        assertThat(outputRegistry.get("rabbitWorker")).isInstanceOf(RabbitWorkOutput.class);
    }

    @Test
    void rejectsDuplicateFactoriesRegardlessOfPriority() {
        WorkerDefinition definition = new WorkerDefinition(
            "priorityWorker",
            Object.class,
            WorkerInputType.SCHEDULER,
            "prio",
            WorkIoBindings.none(),
            Void.class,
            WorkInputConfig.class,
            WorkOutputConfig.class,
            WorkerOutputType.NONE,
            "",
            Set.of()
        );
        WorkerRegistry workerRegistry = new WorkerRegistry(List.of(definition));
        WorkOutputRegistry outputRegistry = new WorkOutputRegistry();
        WorkOutputConfigBinder binder = new WorkOutputConfigBinder(new Binder(new MapConfigurationPropertySource(Map.of())));
        WorkOutput preferredOutput = (result, def) -> { };
        WorkOutputFactory preferred = new OrderedOutputFactory(Ordered.HIGHEST_PRECEDENCE) {
            @Override
            public WorkOutput create(WorkerDefinition def, WorkOutputConfig config) {
                return preferredOutput;
            }
        };
        WorkOutputFactory fallback = new OrderedOutputFactory(Ordered.LOWEST_PRECEDENCE);

        WorkOutputRegistryInitializer initializer = new WorkOutputRegistryInitializer(
            workerRegistry,
            outputRegistry,
            binder,
            List.of(fallback, preferred)
        );
        org.assertj.core.api.Assertions.assertThatThrownBy(initializer::afterSingletonsInstantiated)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Multiple WorkOutputFactory");

    }

    @Test
    void rejectsNonNoneOutputWithoutSupportingFactory() {
        WorkerDefinition definition = new WorkerDefinition(
            "redisWorker",
            Object.class,
            WorkerInputType.SCHEDULER,
            "redis",
            WorkIoBindings.none(),
            Void.class,
            WorkInputConfig.class,
            WorkOutputConfig.class,
            WorkerOutputType.REDIS,
            "",
            Set.of()
        );
        WorkerRegistry workerRegistry = new WorkerRegistry(List.of(definition));
        WorkOutputRegistry outputRegistry = new WorkOutputRegistry();
        WorkOutputConfigBinder binder = new WorkOutputConfigBinder(new Binder(new MapConfigurationPropertySource(Map.of())));

        WorkOutputRegistryInitializer initializer = new WorkOutputRegistryInitializer(
            workerRegistry,
            outputRegistry,
            binder,
            List.of(new NoopWorkOutputFactory())
        );

        assertThatThrownBy(initializer::afterSingletonsInstantiated)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("No WorkOutputFactory found for worker 'redisWorker'")
            .hasMessageContaining("output=REDIS");
    }

    private static class OrderedOutputFactory implements WorkOutputFactory, Ordered {
        private final int order;

        private OrderedOutputFactory(int order) {
            this.order = order;
        }

        @Override
        public boolean supports(WorkerDefinition definition) {
            return true;
        }

        @Override
        public WorkOutput create(WorkerDefinition definition, WorkOutputConfig config) {
            return new NoopWorkOutput();
        }

        @Override
        public int getOrder() {
            return order;
        }
    }
}
