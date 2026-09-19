package io.pockethive.work.config.input;

import static io.pockethive.work.config.WorkConfigurationExpressions.symbolic;

import io.pockethive.work.config.WorkConfigurationException;
import io.pockethive.work.config.WorkConfigurationMode;
import io.pockethive.work.config.WorkConfigurationProblem;
import io.pockethive.work.config.WorkerInputType;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Map;
import java.util.Objects;

/**
 * Responsibility: parse exact integer timing/limit declarations for scheduled Work inputs.
 * Must not: clamp invalid values, render expressions or execute scheduling policies.
 * Contract: RESP-WORK-INPUT-SCHEDULE — docs/architecture/runtime-responsibilities.md#resp-work-input-schedule.
 */
public final class InputScheduleParser {
    public static final String SCHEDULER_MAX_MESSAGES_PATH = "inputs.scheduler.maxMessages";

    public static Object initialValue(WorkerInputType type, InputScheduleField field) {
        if (type == WorkerInputType.CSV_DATASET) return null;
        return switch (field) {
            case INITIAL_DELAY_MS -> 0L;
            case TICK_INTERVAL_MS -> 1000L;
            case MAX_PENDING_TICKS -> 1L;
            case MAX_MESSAGES, STARTUP_DELAY_SECONDS -> null;
        };
    }

    public static Object declaredValue(Map<?, ?> settings, WorkerInputType type, InputScheduleField field) {
        return settings.containsKey(field.key()) ? settings.get(field.key()) : initialValue(type, field);
    }

    public long parse(Object value, InputScheduleField field, String path) {
        var result = validate(value, field, path, WorkConfigurationMode.RESOLVED);
        if (!result.problems().isEmpty()) {
            throw new WorkConfigurationException(result.problems());
        }
        return result.value();
    }

    public InputScheduleValidation validate(Object value, InputScheduleField field, String path,
                                            WorkConfigurationMode mode) {
        Objects.requireNonNull(field, "field");
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(mode, "mode");
        var problems = new ArrayList<WorkConfigurationProblem>();
        var deferred = new ArrayList<String>();
        if (symbolic(value, path, mode, problems, deferred)) {
            return new InputScheduleValidation(null, problems, deferred);
        }
        Long integer = null;
        try {
            if (value instanceof Number number) {
                integer = new BigDecimal(number.toString()).longValueExact();
            } else if (value instanceof String text) {
                integer = new BigDecimal(text.trim()).longValueExact();
            }
        } catch (NumberFormatException | ArithmeticException ignored) {
            // Preserve exact range and integrality without exposing the raw declaration.
        }
        if (integer == null || integer < field.min() || integer > field.max()) {
            problems.add(new WorkConfigurationProblem(path,
                field.key() + " must be an integer >= " + field.min() + " and <= " + field.max() + "."));
        }
        return new InputScheduleValidation(integer, problems, deferred);
    }

    public long startupDelayMillis(Object seconds, String path) {
        return parse(seconds, InputScheduleField.STARTUP_DELAY_SECONDS, path) * 1000;
    }
}
