package io.pockethive.rabbit.api;
import io.pockethive.rabbit.config.RabbitInputSettingsParser;
import io.pockethive.rabbit.config.RabbitOutputSettingsParser;


import io.pockethive.work.config.WorkConfigurationProblem;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Responsibility: export resolved Rabbit settings and reject competing settings in worker environment overrides.
 * Must not: resolve topology, define tuning constraints or read process environment.
 * Contract: RESP-WORK-RABBIT-SETTINGS — docs/architecture/runtime-responsibilities.md#resp-work-rabbit-settings.
 */
public final class RabbitWorkEnvironment {
    private static final Map<String, String> INPUT = Map.of(
        RabbitInputSettingsParser.QUEUE_FIELD, RabbitWorkSettingsBootstrap.INPUT_QUEUE_ENV,
        RabbitInputSettingsParser.PREFETCH_FIELD, "POCKETHIVE_INPUTS_RABBIT_PREFETCH",
        RabbitInputSettingsParser.CONCURRENT_CONSUMERS_FIELD, "POCKETHIVE_INPUTS_RABBIT_CONCURRENTCONSUMERS",
        RabbitInputSettingsParser.EXCLUSIVE_FIELD, "POCKETHIVE_INPUTS_RABBIT_EXCLUSIVE");
    private static final Map<String, String> OUTPUT = Map.of(
        RabbitOutputSettingsParser.EXCHANGE_FIELD, RabbitWorkSettingsBootstrap.OUTPUT_EXCHANGE_ENV,
        RabbitOutputSettingsParser.ROUTING_KEY_FIELD, RabbitWorkSettingsBootstrap.OUTPUT_ROUTING_KEY_ENV,
        RabbitOutputSettingsParser.PERSISTENT_FIELD, "POCKETHIVE_OUTPUTS_RABBIT_PERSISTENT",
        RabbitOutputSettingsParser.PUBLISHER_CONFIRMS_FIELD, "POCKETHIVE_OUTPUTS_RABBIT_PUBLISHERCONFIRMS");

    public Map<String, String> input(Map<?, ?> resolvedSettings) { return encode(resolvedSettings, INPUT); }
    public Map<String, String> output(Map<?, ?> resolvedSettings) { return encode(resolvedSettings, OUTPUT); }

    public List<WorkConfigurationProblem> overrideProblems(Function<String, String> properties) {
        var problems = new ArrayList<WorkConfigurationProblem>(RabbitConnectionEnvironment.workOverrideProblems(properties));
        rejectOverrides("inputs", INPUT, properties, problems);
        rejectOverrides("outputs", OUTPUT, properties, problems);
        return List.copyOf(problems);
    }

    private static Map<String, String> encode(Map<?, ?> settings, Map<String, String> fields) {
        var result = new LinkedHashMap<String, String>();
        fields.forEach((field, environment) -> result.put(environment,
            java.util.Objects.requireNonNull(settings.get(field), field).toString()));
        return Map.copyOf(result);
    }

    private static void rejectOverrides(String direction, Map<String, String> fields,
        Function<String, String> properties, List<WorkConfigurationProblem> problems) {
        fields.forEach((field, environment) -> {
            String path = "pockethive." + direction + ".rabbit."
                + field.replaceAll("([a-z])([A-Z])", "$1-$2").toLowerCase(java.util.Locale.ROOT);
            if (properties.apply(path) != null || properties.apply(environment.toLowerCase(java.util.Locale.ROOT).replace('_', '.')) != null) {
                problems.add(new WorkConfigurationProblem(path,
                    "Rabbit settings must be declared in config; destinations are owned by topology. Environment overrides are unsupported."));
            }
        });
    }
}
