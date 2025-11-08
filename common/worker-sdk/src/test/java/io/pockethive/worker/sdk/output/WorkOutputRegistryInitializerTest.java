package io.pockethive.worker.sdk.output;

import static org.assertj.core.api.Assertions.assertThat;

import io.pockethive.worker.sdk.config.RabbitOutputProperties;
import io.pockethive.worker.sdk.config.WorkInputConfig;
import io.pockethive.worker.sdk.config.WorkOutputConfig;
import io.pockethive.worker.sdk.config.WorkOutputConfigBinder;
import io.pockethive.worker.sdk.config.WorkerCapability;
import io.pockethive.worker.sdk.config.WorkerInputType;
import io.pockethive.worker.sdk.config.WorkerOutputType;
import io.pockethive.worker.sdk.runtime.WorkerDefinition;
import io.pockethive.worker.sdk.runtime.WorkerRegistry;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
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
            null,
            null,
            null,
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
            WorkerInputType.RABBIT,
            "processor",
            null,
            "processor.out",
            "exchange",
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
            "pockethive.outputs.processor.routing-key", "custom.out"
        ));
        WorkOutputConfigBinder binder = new WorkOutputConfigBinder(new Binder(source));
        RabbitTemplate rabbitTemplate = new RabbitTemplate();
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
    void prefersHighestPriorityOutputFactory() {
        WorkerDefinition definition = new WorkerDefinition(
            "priorityWorker",
            Object.class,
            WorkerInputType.SCHEDULER,
            "prio",
            null,
            null,
            null,
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
        initializer.afterSingletonsInstantiated();

        assertThat(outputRegistry.get("priorityWorker")).isSameAs(preferredOutput);
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
