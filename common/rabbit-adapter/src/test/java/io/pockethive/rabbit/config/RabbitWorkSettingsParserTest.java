package io.pockethive.rabbit.config;
import io.pockethive.rabbit.api.RabbitInputSettings;
import io.pockethive.rabbit.api.RabbitInputTuning;
import io.pockethive.rabbit.api.RabbitOutputSettings;
import io.pockethive.rabbit.api.RabbitOutputTuning;


import static org.assertj.core.api.Assertions.assertThat;

import io.pockethive.work.config.WorkConfigurationMode;
import io.pockethive.work.config.WorkerInputType;
import io.pockethive.work.config.WorkerOutputType;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RabbitWorkSettingsParserTest {
    private final RabbitInputSettingsParser inputParser = new RabbitInputSettingsParser();
    private final RabbitOutputSettingsParser outputParser = new RabbitOutputSettingsParser();

    @Test
    void inputProviderMapsRabbitMqAndAppliesCanonicalDefaults() {
        var result = inputParser.validate(
            Map.of("queue", "  inbound.jobs  "),
            "inputs.rabbit",
            WorkConfigurationMode.RESOLVED
        );

        assertThat(inputParser.type()).isEqualTo(WorkerInputType.RABBITMQ);
        assertThat(result.problems()).isEmpty();
        assertThat(result.deferredPaths()).isEmpty();
        assertThat(result.settings()).isEqualTo(new RabbitInputSettings("inbound.jobs", 50, 1, false));
    }

    @Test
    void outputProviderMapsRabbitMqAndAppliesCanonicalDefaults() {
        var result = outputParser.validate(
            Map.of("exchange", "  worker.events  ", "routingKey", " worker.done "),
            "outputs.rabbit",
            WorkConfigurationMode.RESOLVED
        );

        assertThat(outputParser.type()).isEqualTo(WorkerOutputType.RABBITMQ);
        assertThat(result.problems()).isEmpty();
        assertThat(result.deferredPaths()).isEmpty();
        assertThat(result.settings()).isEqualTo(new RabbitOutputSettings("worker.events", "worker.done", true, false));
    }

    @Test
    void acceptsExactCaseInsensitiveBooleanPropertyText() {
        var input = inputParser.validate(
            Map.of("queue", "inbound", "prefetch", 5, "concurrentConsumers", 1, "exclusive", "TRUE"),
            "inputs.rabbit",
            WorkConfigurationMode.RESOLVED
        );
        var output = outputParser.validate(
            Map.of("exchange", "events", "routingKey", "done", "persistent", "FALSE", "publisherConfirms", "true"),
            "outputs.rabbit",
            WorkConfigurationMode.RESOLVED
        );

        assertThat(input.settings()).isEqualTo(new RabbitInputSettings("inbound", 5, 1, true));
        assertThat(output.settings()).isEqualTo(new RabbitOutputSettings("events", "done", false, true));
    }

    @Test
    void rejectsUnknownAndRetiredRabbitFieldsWithoutPartialSettings() {
        var input = inputParser.validate(
            Map.of("queue", "inbound", "deadLetterQueue", "dead", "enabled", true, "autoStartup", false),
            "inputs.rabbit",
            WorkConfigurationMode.RESOLVED
        );
        var output = outputParser.validate(
            Map.of("exchange", "events", "routingKey", "done", "deadLetterQueue", "dead"),
            "outputs.rabbit",
            WorkConfigurationMode.RESOLVED
        );

        assertThat(input.settings()).isNull();
        assertThat(input.problems()).extracting(problem -> problem.path()).containsExactlyInAnyOrder(
            "inputs.rabbit.deadLetterQueue",
            "inputs.rabbit.enabled",
            "inputs.rabbit.autoStartup"
        );
        assertThat(output.settings()).isNull();
        assertThat(output.problems()).extracting(problem -> problem.path())
            .containsExactly("outputs.rabbit.deadLetterQueue");
    }

    @Test
    void rejectsNonPositiveOrNonIntegerTuningAndInvalidBooleanText() {
        var input = inputParser.validate(
            Map.of("queue", "inbound", "prefetch", 0, "concurrentConsumers", 2L, "exclusive", "yes"),
            "inputs.rabbit",
            WorkConfigurationMode.RESOLVED
        );
        var output = outputParser.validate(
            Map.of("exchange", "events", "routingKey", "done", "persistent", " true ", "publisherConfirms", "yes"),
            "outputs.rabbit",
            WorkConfigurationMode.RESOLVED
        );

        assertThat(input.settings()).isNull();
        assertThat(input.problems()).extracting(problem -> problem.path())
            .containsExactlyInAnyOrder(
                "inputs.rabbit.prefetch",
                "inputs.rabbit.concurrentConsumers",
                "inputs.rabbit.exclusive"
            );
        assertThat(output.settings()).isNull();
        assertThat(output.problems()).extracting(problem -> problem.path())
            .containsExactlyInAnyOrder("outputs.rabbit.persistent", "outputs.rabbit.publisherConfirms");
    }

    @Test
    void authoringAcceptsTuningWithoutTopologyAndOnlyDefersAuthoredFields() {
        var defaults = inputParser.validate(Map.of(), "inputs.rabbit", WorkConfigurationMode.AUTHORING);
        assertThat(defaults.problems()).isEmpty();
        assertThat(defaults.deferredPaths()).isEmpty();
        assertThat(defaults.settings()).isEqualTo(new RabbitInputTuning(50, 1, false));
        var symbolic = inputParser.validate(Map.of("prefetch", "{{ rabbit.prefetch }}"),
            "inputs.rabbit", WorkConfigurationMode.AUTHORING);
        assertThat(symbolic.problems()).isEmpty();
        assertThat(symbolic.deferredPaths()).containsExactly("inputs.rabbit.prefetch");
        assertThat(symbolic.settings()).isNull();
        assertThat(outputParser.validate(Map.of(), "outputs.rabbit", WorkConfigurationMode.AUTHORING).settings())
            .isEqualTo(new RabbitOutputTuning(true, false));
    }

    @Test
    void authoringRejectsPhysicalDestinationsIncludingExpressions() {
        var input = inputParser.validate(Map.of("queue", "{{ rabbit.queue }}"),
            "inputs.rabbit", WorkConfigurationMode.AUTHORING);
        assertThat(input.problems()).extracting(p -> p.path()).containsExactly("inputs.rabbit.queue");
        assertThat(input.deferredPaths()).isEmpty();
        var output = outputParser.validate(Map.of("exchange", "exchange", "routingKey", "{{ key }}"),
            "outputs.rabbit", WorkConfigurationMode.AUTHORING);
        assertThat(output.problems()).extracting(p -> p.path())
            .containsExactlyInAnyOrder("outputs.rabbit.exchange", "outputs.rabbit.routingKey");
        assertThat(output.deferredPaths()).isEmpty();
    }
}
