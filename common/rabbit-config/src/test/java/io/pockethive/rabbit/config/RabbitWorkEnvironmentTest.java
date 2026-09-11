package io.pockethive.rabbit.config;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RabbitWorkEnvironmentTest {
    private final RabbitWorkEnvironment environment = new RabbitWorkEnvironment();
    private final RabbitWorkSettingsBootstrap bootstrap = new RabbitWorkSettingsBootstrap(
        new RabbitInputSettingsParser(), new RabbitOutputSettingsParser());

    @Test
    void exportsExactlyTheResolvedSettingsIncludingNonDefaultTuning() {
        var input = bootstrap.input(Map.of("prefetch", 7, "exclusive", true), " queue ");
        assertThat(environment.input(input)).containsExactlyInAnyOrderEntriesOf(Map.of(
            "POCKETHIVE_INPUT_RABBIT_QUEUE", "queue", "POCKETHIVE_INPUTS_RABBIT_PREFETCH", "7",
            "POCKETHIVE_INPUTS_RABBIT_CONCURRENTCONSUMERS", "1", "POCKETHIVE_INPUTS_RABBIT_EXCLUSIVE", "true"));
        var output = bootstrap.output(Map.of("persistent", false, "publisherConfirms", true), "exchange", "route");
        assertThat(environment.output(output)).containsExactlyInAnyOrderEntriesOf(Map.of(
            "POCKETHIVE_OUTPUT_RABBIT_EXCHANGE", "exchange", "POCKETHIVE_OUTPUT_RABBIT_ROUTING_KEY", "route",
            "POCKETHIVE_OUTPUTS_RABBIT_PERSISTENT", "false", "POCKETHIVE_OUTPUTS_RABBIT_PUBLISHERCONFIRMS", "true"));
    }

    @Test
    void rejectsCompetingSettingsButLeavesConnectionOverridesSeparate() {
        assertThat(environment.overrideProblems(Map.of("pockethive.inputs.rabbit.queue", "other")::get))
            .extracting(p -> p.path()).containsExactly("pockethive.inputs.rabbit.queue");
        assertThat(environment.overrideProblems(Map.of("spring.rabbitmq.host", "broker")::get)).isEmpty();
    }
}
