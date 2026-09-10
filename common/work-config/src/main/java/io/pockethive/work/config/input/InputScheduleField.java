package io.pockethive.work.config.input;

import io.pockethive.work.config.WorkerInputType;
import java.util.Set;

/**
 * Responsibility: define the integer timing and limit fields of scheduled Work inputs.
 * Must not: parse declarations, schedule dispatch or hold worker state.
 * Contract: RESP-WORK-INPUT-SCHEDULE — docs/architecture/runtime-responsibilities.md#resp-work-input-schedule.
 */
public enum InputScheduleField {
    INITIAL_DELAY_MS("initialDelayMs", 0, InputScheduleField.MAX_SCHEDULER_MILLIS),
    TICK_INTERVAL_MS("tickIntervalMs", 100, InputScheduleField.MAX_SCHEDULER_MILLIS),
    MAX_PENDING_TICKS("maxPendingTicks", 1, Integer.MAX_VALUE),
    MAX_MESSAGES("maxMessages", 0, Long.MAX_VALUE),
    STARTUP_DELAY_SECONDS("startupDelaySeconds", 0, InputScheduleField.MAX_SCHEDULER_MILLIS / 1000);

    private static final long MAX_SCHEDULER_MILLIS = Long.MAX_VALUE / 1_000_000;

    private final String key;
    private final long min;
    private final long max;

    InputScheduleField(String key, long min, long max) {
        this.key = key;
        this.min = min;
        this.max = max;
    }

    public String key() { return key; }
    public long min() { return min; }
    public long max() { return max; }

    public String path(WorkerInputType type) {
        return "inputs." + type.settingsKey() + "." + key;
    }

    public static Set<InputScheduleField> forInput(WorkerInputType type) {
        return switch (type) {
            case SCHEDULER -> Set.of(INITIAL_DELAY_MS, TICK_INTERVAL_MS, MAX_PENDING_TICKS, MAX_MESSAGES);
            case REDIS_DATASET -> Set.of(INITIAL_DELAY_MS, TICK_INTERVAL_MS);
            case CSV_DATASET -> Set.of(STARTUP_DELAY_SECONDS, TICK_INTERVAL_MS);
            case RABBITMQ -> Set.of();
        };
    }
}
