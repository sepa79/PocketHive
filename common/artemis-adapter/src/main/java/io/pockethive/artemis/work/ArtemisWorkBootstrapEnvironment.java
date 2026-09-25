package io.pockethive.artemis.work;

import static io.pockethive.artemis.config.ArtemisConfigurationFields.*;
import static io.pockethive.artemis.config.ArtemisEnvironmentKeys.*;

import io.pockethive.artemis.api.*;
import io.pockethive.artemis.config.ArtemisInputSettingsParser;
import io.pockethive.artemis.config.ArtemisOutputSettingsParser;
import io.pockethive.work.config.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * Responsibility: combine Artemis authored tuning with resolved topology and export that configuration to ENV.
 * Must not: resolve names, define field constraints, select adapters or open connections.
 * Contract: RESP-ARTEMIS-CONFIGURATION — docs/architecture/runtime-responsibilities.md#resp-artemis-configuration.
 */
public final class ArtemisWorkBootstrapEnvironment implements WorkAdapterEnvironment {
    private final ArtemisConnectionSettings connection;
    private final WorkInputSettingsParser input = ArtemisConfiguration.inputParser();
    private final WorkOutputSettingsParser output = ArtemisConfiguration.outputParser();

    public ArtemisWorkBootstrapEnvironment(ArtemisConnectionSettings connection) {
        this.connection = Objects.requireNonNull(connection, "connection");
    }

    @Override public Map<String, String> connectionEnvironment() {
        var result = new LinkedHashMap<>(ArtemisConnectionEnvironment.encode(connection));
        result.putAll(new WorkPlaneSelection(ArtemisWorkIoType.ARTEMIS).environment());
        return Map.copyOf(result);
    }

    @Override public void validateConnection(Function<String, String> properties) {
        ArtemisConnectionEnvironment.decode(properties);
    }

    @Override public List<WorkConfigurationProblem> overrideProblems(Function<String, String> properties) {
        var problems = new ArrayList<WorkConfigurationProblem>();
        OWNED_PROPERTIES.forEach((path, env) -> {
            if (properties.apply(path) != null
                || properties.apply(env.toLowerCase(Locale.ROOT).replace('_', '.')) != null) {
                problems.add(new WorkConfigurationProblem(path,
                    "Artemis connection and destinations are provisioned; tuning belongs in config. ENV overrides are unsupported."));
            }
        });
        return List.copyOf(problems);
    }

    @Override public WorkBootstrapProjection bootstrap(Map<String, Object> configuration, Map<String, String> destinations) {
        var resolved = new LinkedHashMap<>(configuration);
        var environment = new LinkedHashMap<String, String>();
        Map<?, ?> in = selected(configuration, WorkConfigurationFields.INPUTS);
        if (in != null) {
            var settings = settings(in, WorkConfigurationFields.INPUTS);
            requireValid(input.validate(settings, INPUT_PREFIX, WorkConfigurationMode.AUTHORING).problems());
            settings.put(QUEUE, destinations.get(INPUT_QUEUE));
            var parsed = input.validate(settings, INPUT_PREFIX, WorkConfigurationMode.RESOLVED);
            requireValid(parsed.problems());
            var value = (ArtemisInputSettings) Objects.requireNonNull(parsed.settings(), "Resolved Artemis input");
            resolved.put(WorkConfigurationFields.INPUTS, root(in, ArtemisInputSettingsParser.configuration(value)));
            environment.put(INPUT_QUEUE, value.queue());
            environment.put(INPUT_WINDOW, Integer.toString(value.consumerWindowBytes()));
        }
        Map<?, ?> out = selected(configuration, WorkConfigurationFields.OUTPUTS);
        if (out != null) {
            var settings = settings(out, WorkConfigurationFields.OUTPUTS);
            requireValid(output.validate(settings, OUTPUT_PREFIX, WorkConfigurationMode.AUTHORING).problems());
            settings.put(ADDRESS, destinations.get(OUTPUT_ADDRESS));
            var parsed = output.validate(settings, OUTPUT_PREFIX, WorkConfigurationMode.RESOLVED);
            requireValid(parsed.problems());
            var value = (ArtemisOutputSettings) Objects.requireNonNull(parsed.settings(), "Resolved Artemis output");
            resolved.put(WorkConfigurationFields.OUTPUTS, root(out, ArtemisOutputSettingsParser.configuration(value)));
            environment.put(OUTPUT_ADDRESS, value.address());
            environment.put(OUTPUT_PERSISTENT, Boolean.toString(value.persistent()));
        }
        return new WorkBootstrapProjection(resolved, environment);
    }

    private static Map<?, ?> selected(Map<String, Object> config, String direction) {
        if (!(config.get(direction) instanceof Map<?, ?> fields)) return null;
        Object type = fields.get(WorkConfigurationFields.TYPE);
        return type instanceof String text && WorkIoTypeParser.matches(text, ArtemisWorkIoType.ARTEMIS) ? fields : null;
    }

    private static LinkedHashMap<String, Object> settings(Map<?, ?> root, String direction) {
        Object value = root.get(ArtemisWorkIoType.ARTEMIS.settingsKey());
        if (!(value instanceof Map<?, ?> fields)) {
            throw new WorkConfigurationException(List.of(new WorkConfigurationProblem(direction,
                "Explicit Artemis settings are required.")));
        }
        var result = new LinkedHashMap<String, Object>();
        fields.forEach((key, field) -> result.put(Objects.toString(key), field));
        return result;
    }

    private static Map<String, Object> root(Map<?, ?> source, Map<String, Object> settings) {
        var result = new LinkedHashMap<String, Object>();
        source.forEach((key, value) -> result.put(Objects.toString(key), value));
        result.put(WorkConfigurationFields.TYPE, ArtemisWorkIoType.ARTEMIS.name());
        result.put(ArtemisWorkIoType.ARTEMIS.settingsKey(), settings);
        return Map.copyOf(result);
    }

    private static void requireValid(List<WorkConfigurationProblem> problems) {
        if (!problems.isEmpty()) throw new WorkConfigurationException(problems);
    }
}
