package io.pockethive.work.config.scheduler;

import static io.pockethive.work.config.WorkConfigurationExpressions.symbolic;
import static io.pockethive.work.config.input.InputScheduleField.*;

import io.pockethive.work.config.WorkConfigurationException;
import io.pockethive.work.config.WorkConfigurationMode;
import io.pockethive.work.config.WorkConfigurationProblem;
import io.pockethive.work.config.WorkerInputType;
import io.pockethive.work.config.input.InputRateParser;
import io.pockethive.work.config.input.InputScheduleField;
import io.pockethive.work.config.input.InputScheduleParser;
import io.pockethive.work.config.input.SchedulerResetParser;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Responsibility: compose complete scheduler settings through canonical field parsers.
 * Must not: duplicate numeric/default/reset semantics, bind Spring properties or schedule work.
 * Contract: RESP-WORK-SCHEDULER-SETTINGS — docs/architecture/runtime-responsibilities.md#resp-work-scheduler-settings.
 */
public final class SchedulerSettingsParser {
    public static final String PATH = "inputs.scheduler";
    private static final Set<String> FIELDS = Set.of(InputRateParser.FIELD, INITIAL_DELAY_MS.key(),
        TICK_INTERVAL_MS.key(), MAX_PENDING_TICKS.key(), MAX_MESSAGES.key(), SchedulerResetParser.FIELD);

    public SchedulerSettings parse(Object value, String path) {
        var result = validate(value, path, WorkConfigurationMode.RESOLVED);
        if (!result.problems().isEmpty()) throw new WorkConfigurationException(result.problems());
        return result.settings();
    }

    public SchedulerSettingsValidation validate(Object value, String path, WorkConfigurationMode mode) {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(mode, "mode");
        var problems = new ArrayList<WorkConfigurationProblem>();
        var deferred = new ArrayList<String>();
        if (symbolic(value, path, mode, problems, deferred)) return new SchedulerSettingsValidation(null, problems, deferred);
        if (!(value instanceof Map<?, ?> fields)) {
            return new SchedulerSettingsValidation(null,
                List.of(new WorkConfigurationProblem(path, "Scheduler settings must be an object.")), List.of());
        }
        fields.keySet().stream().filter(key -> key == null || !FIELDS.contains(key)).forEach(key ->
            problems.add(new WorkConfigurationProblem(path + "." + key, "Unsupported scheduler setting.")));
        var rate = new InputRateParser().validate(fields.get(InputRateParser.FIELD), path + "." + InputRateParser.FIELD, mode);
        problems.addAll(rate.problems());
        deferred.addAll(rate.deferredPaths());
        var numbers = new EnumMap<InputScheduleField, Long>(InputScheduleField.class);
        var schedule = new InputScheduleParser();
        for (var field : InputScheduleField.forInput(WorkerInputType.SCHEDULER)) {
            var result = schedule.validate(InputScheduleParser.declaredValue(fields, WorkerInputType.SCHEDULER, field),
                field, path + "." + field.key(), mode);
            numbers.put(field, result.value());
            problems.addAll(result.problems());
            deferred.addAll(result.deferredPaths());
        }
        if (fields.containsKey(SchedulerResetParser.FIELD)) {
            var reset = new SchedulerResetParser().validate(fields.get(SchedulerResetParser.FIELD),
                path + "." + SchedulerResetParser.FIELD, mode);
            problems.addAll(reset.problems());
            deferred.addAll(reset.deferredPaths());
        }
        var settings = problems.isEmpty() && deferred.isEmpty()
            ? new SchedulerSettings(rate.ratePerSec(), numbers.get(INITIAL_DELAY_MS), numbers.get(TICK_INTERVAL_MS),
                numbers.get(MAX_PENDING_TICKS).intValue(), numbers.get(MAX_MESSAGES)) : null;
        return new SchedulerSettingsValidation(settings, problems, deferred);
    }
}
