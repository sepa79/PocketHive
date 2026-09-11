package io.pockethive.rabbit.config;

import io.pockethive.work.config.WorkConfigurationException;
import io.pockethive.work.config.WorkConfigurationMode;
import io.pockethive.work.config.WorkConfigurationProblem;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Responsibility: project explicit resolved Rabbit topology and optional adapter tuning into complete settings.
 * Must not: infer topology, read environment, select transports or access Rabbit.
 * Contract: RESP-WORK-RABBIT-SETTINGS — docs/architecture/runtime-responsibilities.md#resp-work-rabbit-settings.
 */
public final class RabbitWorkSettingsBootstrap {
    public static final String INPUT_QUEUE_ENV = "POCKETHIVE_INPUT_RABBIT_QUEUE";
    public static final String OUTPUT_EXCHANGE_ENV = "POCKETHIVE_OUTPUT_RABBIT_EXCHANGE";
    public static final String OUTPUT_ROUTING_KEY_ENV = "POCKETHIVE_OUTPUT_RABBIT_ROUTING_KEY";

    private final RabbitInputSettingsParser inputParser;
    private final RabbitOutputSettingsParser outputParser;

    public RabbitWorkSettingsBootstrap(RabbitInputSettingsParser inputParser, RabbitOutputSettingsParser outputParser) {
        this.inputParser = Objects.requireNonNull(inputParser, "inputParser");
        this.outputParser = Objects.requireNonNull(outputParser, "outputParser");
    }

    public Map<String, Object> input(Object declaredTuning, String queue) {
        var authored = inputParser.validate(tuning(declaredTuning, "inputs.rabbit"), "inputs.rabbit", WorkConfigurationMode.AUTHORING);
        if (!authored.problems().isEmpty()) throw new WorkConfigurationException(authored.problems());

        var candidate = tuning(declaredTuning, "inputs.rabbit");
        candidate.put(RabbitInputSettingsParser.QUEUE_FIELD, queue);
        var result = inputParser.validate(candidate, "inputs.rabbit", WorkConfigurationMode.RESOLVED);
        if (!result.problems().isEmpty()) throw new WorkConfigurationException(result.problems());
        return RabbitInputSettingsParser.configuration((RabbitInputSettings) result.settings());
    }

    public Map<String, Object> output(Object declaredTuning, String exchange, String routingKey) {
        var authored = outputParser.validate(tuning(declaredTuning, "outputs.rabbit"), "outputs.rabbit", WorkConfigurationMode.AUTHORING);
        if (!authored.problems().isEmpty()) throw new WorkConfigurationException(authored.problems());


        var candidate = tuning(declaredTuning, "outputs.rabbit");
        candidate.put(RabbitOutputSettingsParser.EXCHANGE_FIELD, exchange);
        candidate.put(RabbitOutputSettingsParser.ROUTING_KEY_FIELD, routingKey);
        var result = outputParser.validate(candidate, "outputs.rabbit", WorkConfigurationMode.RESOLVED);
        if (!result.problems().isEmpty()) throw new WorkConfigurationException(result.problems());
        return RabbitOutputSettingsParser.configuration((RabbitOutputSettings) result.settings());
    }

    private static Map<String, Object> tuning(Object declared, String path) {
        if (!(declared instanceof Map<?, ?> fields)) fail(path, "Rabbit tuning settings must be an object.");
        var copy = new LinkedHashMap<String, Object>();
        ((Map<?, ?>) declared).forEach((key, value) -> copy.put(Objects.toString(key), value));
        return copy;
    }

    private static void fail(String path, String message) {
        throw new WorkConfigurationException(List.of(new WorkConfigurationProblem(path, message)));
    }
}
