package io.pockethive.work.config.csv;

import static io.pockethive.work.config.csv.CsvDatasetParser.*;

import io.pockethive.work.config.WorkConfigurationException;
import io.pockethive.work.config.WorkConfigurationProblem;
import io.pockethive.work.config.WorkerInputType;
import io.pockethive.work.config.input.InputRateParser;
import io.pockethive.work.config.input.InputScheduleField;
import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Responsibility: map CSV startup properties and project the validated final environment into bootstrap.
 * Must not: read process settings, resolve placeholders, duplicate CSV constraints or access files.
 * Contract: RESP-WORK-CSV-SETTINGS — docs/architecture/runtime-responsibilities.md#resp-work-csv-settings.
 */
public final class CsvDatasetEnvironment {
    private static final Map<String, String> PROPERTIES = Map.of(
        FILE_PATH, "file-path", ROTATE, "rotate", SKIP_HEADER, "skip-header", DELIMITER, "delimiter",
        CHARSET, "charset", InputRateParser.FIELD, "rate-per-sec",
        InputScheduleField.STARTUP_DELAY_SECONDS.key(), "startup-delay-seconds",
        InputScheduleField.TICK_INTERVAL_MS.key(), "tick-interval-ms");

    public Map<String, Object> candidate(Object inputs, Function<String, String> overrides) {
        var candidate = new LinkedHashMap<String, Object>();
        if (inputs instanceof Map<?, ?> io && io.containsKey("csv")) {
            if (!(io.get("csv") instanceof Map<?, ?> fields)) throw invalid(PATH, "CSV settings must be an object.");
            fields.forEach((key, value) -> {
                if (!(key instanceof String field)) throw invalid(PATH, "CSV keys must be text.");
                candidate.put(field, value);
            });
        }
        PROPERTIES.forEach((field, property) -> {
            String value = overrides.apply(propertyName(property));
            if (value != null) candidate.put(field, value);
        });
        return Collections.unmodifiableMap(candidate);
    }

    public Map<String, String> encode(Map<String, Object> candidate) {
        var environment = new LinkedHashMap<String, String>();
        PROPERTIES.forEach((field, property) -> {
            Object value = candidate.get(field);
            if (value instanceof String || value instanceof Boolean || value instanceof Number) {
                String text = value.toString();
                if (value instanceof Number) {
                    try { text = new BigDecimal(text).stripTrailingZeros().toPlainString(); }
                    catch (NumberFormatException ignored) { /* Parser reports invalid numeric values. */ }
                }
                environment.put("POCKETHIVE_INPUTS_CSV_" + property.replace("-", "").toUpperCase(java.util.Locale.ROOT), text);
            }
        });
        return Map.copyOf(environment);
    }

    public Map<String, Object> resolve(Map<String, Object> configuration, Map<String, Object> candidate,
                                        Function<String, String> finalProperties) {
        Object inputs = configuration.get("inputs");
        boolean declared = inputs instanceof Map<?, ?> io && io.containsKey("csv");
        String selected = finalProperties.apply("pockethive.inputs.type");
        if (!declared && candidate.isEmpty() && !(selected != null && WorkerInputType.CSV_DATASET.name().equalsIgnoreCase(selected.trim()))) return configuration;
        var resolved = new LinkedHashMap<String, Object>(candidate);
        PROPERTIES.forEach((field, property) -> {
            if (candidate.get(field) instanceof String) resolved.put(field, finalProperties.apply(propertyName(property)));
        });
        var settings = new CsvDatasetParser().parse(resolved, PATH);
        var io = new LinkedHashMap<Object, Object>();
        if (inputs instanceof Map<?, ?> original) io.putAll(original);
        io.put("csv", CsvDatasetParser.configuration(settings));
        var bootstrap = new LinkedHashMap<>(configuration);
        bootstrap.put("inputs", Collections.unmodifiableMap(io));
        return Collections.unmodifiableMap(bootstrap);
    }

    private static String propertyName(String property) { return "pockethive.inputs.csv." + property; }

    private static WorkConfigurationException invalid(String path, String message) {
        return new WorkConfigurationException(List.of(new WorkConfigurationProblem(path, message)));
    }
}
