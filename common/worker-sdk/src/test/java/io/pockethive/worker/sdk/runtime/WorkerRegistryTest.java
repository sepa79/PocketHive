package io.pockethive.worker.sdk.runtime;

import io.pockethive.worker.sdk.config.WorkerType;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkerRegistryTest {

    private static final WorkerDefinition GENERATOR_TRIGGER = new WorkerDefinition(
        "triggerWorker",
        Object.class,
        WorkerType.GENERATOR,
        "trigger",
        null,
        null,
        null,
        Void.class
    );

    private static final WorkerDefinition MESSAGE_PROCESSOR = new WorkerDefinition(
        "processorWorker",
        Object.class,
        WorkerType.MESSAGE,
        "processor",
        "in",
        "out",
        "exchange.hive",
        Void.class
    );

    private static final WorkerDefinition MESSAGE_TRIGGER = new WorkerDefinition(
        "triggerMessageWorker",
        Object.class,
        WorkerType.MESSAGE,
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
    void findByRoleAndTypeReturnsMatchingDefinition() {
        Optional<WorkerDefinition> result = registry.findByRoleAndType("processor", WorkerType.MESSAGE);

        assertThat(result).contains(MESSAGE_PROCESSOR);
    }

    @Test
    void findByRoleAndTypeReturnsEmptyWhenNoMatch() {
        Optional<WorkerDefinition> result = registry.findByRoleAndType("missing", WorkerType.MESSAGE);

        assertThat(result).isEmpty();
    }

    @Test
    void streamByRoleFiltersDefinitions() {
        assertThat(registry.streamByRole("trigger").toList())
            .containsExactlyInAnyOrder(GENERATOR_TRIGGER, MESSAGE_TRIGGER);
    }

    @Test
    void streamByRoleAndTypeFiltersDefinitions() {
        assertThat(registry.streamByRoleAndType("trigger", WorkerType.GENERATOR).toList())
            .containsExactly(GENERATOR_TRIGGER);
    }

    @Test
    void streamByRoleRejectsBlankRole() {
        assertThatThrownBy(() -> registry.streamByRole(" "))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("role");
    }

    @Test
    void streamByRoleAndTypeRejectsNullType() {
        assertThatThrownBy(() -> registry.streamByRoleAndType("trigger", null))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("type");
    }
}
