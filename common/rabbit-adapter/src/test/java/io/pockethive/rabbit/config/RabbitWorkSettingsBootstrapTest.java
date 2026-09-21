package io.pockethive.rabbit.config;
import io.pockethive.rabbit.api.RabbitWorkSettingsBootstrap;


import io.pockethive.work.config.WorkConfigurationException;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RabbitWorkSettingsBootstrapTest {
    private final RabbitWorkSettingsBootstrap bootstrap = new RabbitWorkSettingsBootstrap(
        new RabbitInputSettingsParser(), new RabbitOutputSettingsParser());

    @Test
    void rejectsExplicitNullTuningRatherThanDefaulting() {
        assertThatThrownBy(() -> bootstrap.input(null, "queue"))
            .isInstanceOf(WorkConfigurationException.class).hasMessageContaining("inputs.rabbit");
        assertThatThrownBy(() -> bootstrap.output(null, "exchange", "route"))
            .isInstanceOf(WorkConfigurationException.class).hasMessageContaining("outputs.rabbit");
    }

    @Test
    void projectsInputDefaultsFromTopology() {
        assertThat(bootstrap.input(Map.of(), " queue ")).containsExactlyInAnyOrderEntriesOf(Map.of(
            "queue", "queue", "prefetch", 50, "concurrentConsumers", 1, "exclusive", false));
    }

    @Test
    void projectsOutputDefaultsFromTopology() {
        assertThat(bootstrap.output(Map.of(), " exchange ", " key ")).containsExactlyInAnyOrderEntriesOf(Map.of(
            "exchange", "exchange", "routingKey", "key", "persistent", true, "publisherConfirms", false));
    }

    @Test
    void retainsDeclaredTuning() {
        assertThat(bootstrap.input(Map.of("prefetch", 10, "exclusive", "true"), "queue"))
            .containsEntry("prefetch", 10).containsEntry("exclusive", true);
    }

    @Test
    void rejectsDeclaredDestinationConflict() {
        assertThatThrownBy(() -> bootstrap.output(Map.of("exchange", "other"), "exchange", "key"))
            .isInstanceOf(WorkConfigurationException.class).hasMessageContaining("owned by topology");
    }

    @Test
    void rejectsInvalidTopologyAndNonMapDeclaration() {
        assertThatThrownBy(() -> bootstrap.input(Map.of(), " ")).isInstanceOf(WorkConfigurationException.class)
            .hasMessageContaining("inputs.rabbit.queue");
        assertThatThrownBy(() -> bootstrap.output("invalid", "exchange", "key"))
            .isInstanceOf(WorkConfigurationException.class).hasMessageContaining("outputs.rabbit");
    }
}
