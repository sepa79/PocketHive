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
}
