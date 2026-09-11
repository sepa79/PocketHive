package io.pockethive.work.local.scheduler;

import io.pockethive.work.config.WorkConfigurationException;
import io.pockethive.work.config.WorkConfigurationProblem;
import io.pockethive.work.config.WorkerInputType;
import io.pockethive.work.config.input.InputRateParser;
import io.pockethive.work.config.input.InputScheduleField;
import io.pockethive.work.config.input.InputScheduleParser;
import io.pockethive.work.local.scheduler.SchedulerResetParser;
import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Responsibility: compose scheduler startup properties and project final validated settings into bootstrap.
 * Must not: resolve Spring placeholders, duplicate scheduler constraints or export runtime reset commands.
 * Contract: RESP-WORK-SCHEDULER-SETTINGS — docs/architecture/runtime-responsibilities.md#resp-work-scheduler-settings.
 */
public final class SchedulerSettingsEnvironment {
    private static final Map<String, String> PROPERTIES = Map.of(
        InputRateParser.FIELD, "rate-per-sec",
        InputScheduleField.INITIAL_DELAY_MS.key(), "initial-delay-ms",
        InputScheduleField.TICK_INTERVAL_MS.key(), "tick-interval-ms",
        InputScheduleField.MAX_PENDING_TICKS.key(), "max-pending-ticks",
        InputScheduleField.MAX_MESSAGES.key(), "max-messages");

    public Map<String, Object> candidate(Object inputs, Function<String, String> overrides) {
        var candidate = new LinkedHashMap<String, Object>();
        boolean declared = false;
        if (inputs instanceof Map<?, ?> io && io.containsKey("scheduler")) {
            declared = true;
            if (!(io.get("scheduler") instanceof Map<?, ?> fields)) {
                throw invalid(SchedulerSettingsParser.PATH, "Scheduler settings must be an object.");
            }
            fields.forEach((key, value) -> {
                if (!(key instanceof String field)) {
                    throw invalid(SchedulerSettingsParser.PATH, "Scheduler keys must be text.");
                }
                candidate.put(field, value);
            });
        }
        String type = overrides.apply("pockethive.inputs.type");
        boolean selected = type != null && WorkerInputType.SCHEDULER.name().equalsIgnoreCase(type.trim());
        if (!declared && !selected) return Map.of();
        putDefaults(candidate);
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
                environment.put(environmentName(property), text(value));
            }
        });
        return Map.copyOf(environment);
    }

    public Map<String, Object> resolve(Map<String, Object> configuration, Map<String, Object> candidate,
                                        Function<String, String> finalProperties) {
        Object inputs = configuration.get("inputs");
        boolean declared = inputs instanceof Map<?, ?> io && io.containsKey("scheduler");
        String type = finalProperties.apply("pockethive.inputs.type");
        boolean selected = type != null && WorkerInputType.SCHEDULER.name().equalsIgnoreCase(type.trim());
        if (!declared && !selected && candidate.isEmpty()) return configuration;
        var resolved = new LinkedHashMap<String, Object>(candidate);
        PROPERTIES.forEach((field, property) -> {
            if (candidate.get(field) instanceof String) {
                resolved.put(field, finalProperties.apply(propertyName(property)));
            }
        });
        var settings = new SchedulerSettingsParser().parse(resolved, SchedulerSettingsParser.PATH);
        var scheduler = configuration(settings);
        if (candidate.containsKey(SchedulerResetParser.FIELD)) {
            scheduler.put(SchedulerResetParser.FIELD, candidate.get(SchedulerResetParser.FIELD));
        }
        var resolvedInputs = new LinkedHashMap<Object, Object>();
        if (inputs instanceof Map<?, ?> original) resolvedInputs.putAll(original);
        resolvedInputs.put("scheduler", Collections.unmodifiableMap(scheduler));
        var bootstrap = new LinkedHashMap<>(configuration);
        bootstrap.put("inputs", Collections.unmodifiableMap(resolvedInputs));
        return Collections.unmodifiableMap(bootstrap);
    }

    private static void putDefaults(Map<String, Object> candidate) {
        for (InputScheduleField field : List.of(InputScheduleField.INITIAL_DELAY_MS,
            InputScheduleField.TICK_INTERVAL_MS, InputScheduleField.MAX_PENDING_TICKS)) {
            if (!candidate.containsKey(field.key())) {
                candidate.put(field.key(), InputScheduleParser.initialValue(WorkerInputType.SCHEDULER, field));
            }
        }
    }

    private static Map<String, Object> configuration(SchedulerSettings settings) {
        var configuration = new LinkedHashMap<String, Object>();
        configuration.put(InputRateParser.FIELD, settings.ratePerSec());
        configuration.put(InputScheduleField.INITIAL_DELAY_MS.key(), settings.initialDelayMs());
        configuration.put(InputScheduleField.TICK_INTERVAL_MS.key(), settings.tickIntervalMs());
        configuration.put(InputScheduleField.MAX_PENDING_TICKS.key(), settings.maxPendingTicks());
        configuration.put(InputScheduleField.MAX_MESSAGES.key(), settings.maxMessages());
        return configuration;
    }

    private static String propertyName(String property) { return "pockethive.inputs.scheduler." + property; }

    private static String environmentName(String property) {
        return "POCKETHIVE_INPUTS_SCHEDULER_" + property.replace("-", "").toUpperCase(java.util.Locale.ROOT);
    }

    private static String text(Object value) {
        if (!(value instanceof Number)) return value.toString();
        try {
            return new BigDecimal(value.toString()).stripTrailingZeros().toPlainString();
        } catch (NumberFormatException ignored) {
            return value.toString();
        }
    }

    private static WorkConfigurationException invalid(String path, String message) {
        return new WorkConfigurationException(List.of(new WorkConfigurationProblem(path, message)));
    }
}
