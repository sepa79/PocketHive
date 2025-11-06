package io.pockethive.worker.sdk.runtime;

import io.pockethive.worker.sdk.config.WorkerInputType;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkerRegistryTest {

    private static final WorkerDefinition GENERATOR_TRIGGER = new WorkerDefinition(
        "triggerWorker",
        Object.class,
        WorkerInputType.SCHEDULER,
        "trigger",
        null,
        null,
        null,
        Void.class
    );

    private static final WorkerDefinition MESSAGE_PROCESSOR = new WorkerDefinition(
        "processorWorker",
        Object.class,
        WorkerInputType.RABBIT,
        "processor",
        "in",
        "out",
        "exchange.hive",
        Void.class
    );

    private static final WorkerDefinition MESSAGE_TRIGGER = new WorkerDefinition(
        "triggerMessageWorker",
        Object.class,
        WorkerInputType.RABBIT,
        "trigger",
        null,
        null,
        null,
        Void.class
    );

    private final WorkerRegistry registry = new WorkerRegistry(List.of(
        GENERATOR_TRIGGER,
        MESSAGE_PROCESSOR,
        MESSAGE_TRIGGER
    ));

    @Test
    void findByRoleAndInputReturnsMatchingDefinition() {
        Optional<WorkerDefinition> result = registry.findByRoleAndInput("processor", WorkerInputType.RABBIT);

        assertThat(result).contains(MESSAGE_PROCESSOR);
    }

    @Test
    void findByRoleAndInputReturnsEmptyWhenNoMatch() {
        Optional<WorkerDefinition> result = registry.findByRoleAndInput("missing", WorkerInputType.RABBIT);

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
