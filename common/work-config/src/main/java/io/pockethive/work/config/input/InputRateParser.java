package io.pockethive.work.config.input;

import static io.pockethive.work.config.WorkConfigurationExpressions.symbolic;

import io.pockethive.work.config.WorkConfigurationException;
import io.pockethive.work.config.WorkConfigurationMode;
import io.pockethive.work.config.WorkConfigurationProblem;
import io.pockethive.work.config.WorkerInputType;
import java.util.ArrayList;
import java.util.Map;
import java.util.Objects;

/**
 * Responsibility: parse and validate the rate shared by scheduled Work inputs.
 * Must not: bind environment properties, render expressions or decide dispatch timing.
 * Contract: RESP-WORK-INPUT-RATE — docs/architecture/runtime-responsibilities.md#resp-work-input-rate.
 */
public final class InputRateParser {
    public static final String FIELD = "ratePerSec";
    public static final String SCHEDULER_PATH = "inputs.scheduler.ratePerSec";
    public static final String REDIS_PATH = "inputs.redis.ratePerSec";
    public static final String CSV_PATH = "inputs.csv.ratePerSec";
    public static final Map<String, String> PATHS_BY_INPUT = Map.of(
        WorkerInputType.SCHEDULER.name(), SCHEDULER_PATH,
        WorkerInputType.REDIS_DATASET.name(), REDIS_PATH,
        WorkerInputType.CSV_DATASET.name(), CSV_PATH);

    public double parse(Object value, String path) {
        var result = validate(value, path, WorkConfigurationMode.RESOLVED);
        if (!result.problems().isEmpty()) {
            throw new WorkConfigurationException(result.problems());
        }
        return result.ratePerSec();
    }

    public InputRateValidation validate(Object value, String path, WorkConfigurationMode mode) {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(mode, "mode");
        var problems = new ArrayList<WorkConfigurationProblem>();
        var deferred = new ArrayList<String>();
        if (symbolic(value, path, mode, problems, deferred)) {
            return new InputRateValidation(null, problems, deferred);
        }
        Double rate = null;
        if (value instanceof Number number) {
            rate = number.doubleValue();
        } else if (value instanceof String text) {
            try {
                rate = Double.parseDouble(text.trim());
            } catch (NumberFormatException ignored) {
                // Report the same safe contract error for every malformed declaration.
            }
        }
        if (rate == null || !Double.isFinite(rate) || rate < 0.0) {
            problems.add(new WorkConfigurationProblem(path, "ratePerSec must be a finite number >= 0.0."));
        }
        return new InputRateValidation(rate, problems, deferred);
    }
}
