package io.pockethive.worker.sdk.runtime;

import io.pockethive.worker.sdk.config.WorkInputConfig;
import io.pockethive.worker.sdk.config.WorkOutputConfig;
import io.pockethive.worker.sdk.config.WorkerCapability;
import io.pockethive.worker.sdk.config.WorkerInputType;
import io.pockethive.worker.sdk.config.WorkerOutputType;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkerRegistryTest {

    private static final WorkerDefinition GENERATOR_TRIGGER = new WorkerDefinition(
        "triggerWorker",
        Object.class,
        WorkerInputType.SCHEDULER,
        "trigger",
        WorkIoBindings.none(),
        Void.class,
        WorkInputConfig.class,
        WorkOutputConfig.class,
        WorkerOutputType.NONE,
        "Trigger worker",
        Set.of(WorkerCapability.SCHEDULER)
    );

    private static final WorkerDefinition MESSAGE_PROCESSOR = new WorkerDefinition(
        "processorWorker",
        Object.class,
        WorkerInputType.RABBITMQ,
        "processor",
        WorkIoBindings.of("in", "out", "exchange.hive"),
        Void.class,
        WorkInputConfig.class,
        WorkOutputConfig.class,
        WorkerOutputType.RABBITMQ,
        "Processor worker",
        Set.of(WorkerCapability.MESSAGE_DRIVEN)
    );

    private static final WorkerDefinition MESSAGE_TRIGGER = new WorkerDefinition(
        "triggerMessageWorker",
        Object.class,
        WorkerInputType.RABBITMQ,
        "trigger",
        WorkIoBindings.none(),
        Void.class,
        WorkInputConfig.class,
        WorkOutputConfig.class,
        WorkerOutputType.NONE,
        "Message trigger worker",
        Set.of(WorkerCapability.MESSAGE_DRIVEN)
    );

    private final WorkerRegistry registry = new WorkerRegistry(List.of(
        GENERATOR_TRIGGER,
        MESSAGE_PROCESSOR,
        MESSAGE_TRIGGER
    ));

    @Test
    void findByRoleAndInputReturnsMatchingDefinition() {
        Optional<WorkerDefinition> result = registry.findByRoleAndInput("processor", WorkerInputType.RABBITMQ);

        assertThat(result).contains(MESSAGE_PROCESSOR);
    }

    @Test
    void findByRoleAndInputReturnsEmptyWhenNoMatch() {
        Optional<WorkerDefinition> result = registry.findByRoleAndInput("missing", WorkerInputType.RABBITMQ);

        assertThat(result).isEmpty();
    }

    @Test
    void streamByRoleFiltersDefinitions() {
        assertThat(registry.streamByRole("trigger").toList())
            .containsExactlyInAnyOrder(GENERATOR_TRIGGER, MESSAGE_TRIGGER);
    }

    @Test
    void streamByRoleAndInputFiltersDefinitions() {
        assertThat(registry.streamByRoleAndInput("trigger", WorkerInputType.SCHEDULER).toList())
            .containsExactly(GENERATOR_TRIGGER);
    }

    @Test
    void streamByRoleRejectsBlankRole() {
        assertThatThrownBy(() -> registry.streamByRole(" "))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("role");
    }

    @Test
    void streamByRoleAndInputRejectsNullType() {
        assertThatThrownBy(() -> registry.streamByRoleAndInput("trigger", null))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("input");
    }
}
