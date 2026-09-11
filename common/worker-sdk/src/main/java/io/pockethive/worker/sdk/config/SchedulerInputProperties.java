package io.pockethive.worker.sdk.config;

import java.util.LinkedHashMap;
import java.util.Map;
import io.pockethive.work.local.scheduler.SchedulerSettings;
import io.pockethive.work.local.scheduler.SchedulerSettingsParser;
import io.pockethive.work.config.input.InputRateParser;
import io.pockethive.work.config.input.InputScheduleField;
import io.pockethive.work.config.input.InputScheduleParser;
import io.pockethive.work.config.WorkerInputType;

/**
 * Responsibility: bind scheduler startup settings and delegate rate/timing/limit validation to work-config.
 * Must not: implement rate/timing/limit constraints or schedule work.
 * Consumes RESP-WORK-SCHEDULER-SETTINGS for complete startup validation.
 * Worker enablement belongs to RESP-WORK-STATE, never these input properties.
 * Contract: RESP-WORK-IO-CONFIG — docs/architecture/runtime-responsibilities.md#resp-work-io-config.
 * Consumes RESP-WORK-INPUT-RATE and RESP-WORK-INPUT-SCHEDULE:
 * docs/architecture/runtime-responsibilities.md#resp-work-input-schedule.
 */
public class SchedulerInputProperties implements WorkInputConfig {

    private Object initialDelayMs =
        InputScheduleParser.initialValue(WorkerInputType.SCHEDULER, InputScheduleField.INITIAL_DELAY_MS);
    private Object tickIntervalMs =
        InputScheduleParser.initialValue(WorkerInputType.SCHEDULER, InputScheduleField.TICK_INTERVAL_MS);
    private Object maxPendingTicks =
        InputScheduleParser.initialValue(WorkerInputType.SCHEDULER, InputScheduleField.MAX_PENDING_TICKS);
    private Object ratePerSec;
    /**
     * Required limit on the total number of messages the scheduler will
     * dispatch for the current configuration. A value of {@code 0} means
     * "no limit" (infinite run).
     */
    private Object maxMessages =
        InputScheduleParser.initialValue(WorkerInputType.SCHEDULER, InputScheduleField.MAX_MESSAGES);

    public Object getInitialDelayMs() {
        return initialDelayMs;
    }

    public void setInitialDelayMs(Object initialDelayMs) {
        this.initialDelayMs = initialDelayMs;
    }

    public long initialDelayMs() {
        return new InputScheduleParser().parse(initialDelayMs, InputScheduleField.INITIAL_DELAY_MS,
            InputScheduleField.INITIAL_DELAY_MS.path(WorkerInputType.SCHEDULER));
    }

    public Object getTickIntervalMs() {
        return tickIntervalMs;
    }

    public void setTickIntervalMs(Object tickIntervalMs) {
        this.tickIntervalMs = tickIntervalMs;
    }

    public long tickIntervalMs() {
        return new InputScheduleParser().parse(tickIntervalMs, InputScheduleField.TICK_INTERVAL_MS,
            InputScheduleField.TICK_INTERVAL_MS.path(WorkerInputType.SCHEDULER));
    }

    public Object getMaxPendingTicks() {
        return maxPendingTicks;
    }

    public void setMaxPendingTicks(Object maxPendingTicks) {
        this.maxPendingTicks = maxPendingTicks;
    }

    public int maxPendingTicks() {
        return (int) new InputScheduleParser().parse(maxPendingTicks, InputScheduleField.MAX_PENDING_TICKS,
            InputScheduleField.MAX_PENDING_TICKS.path(WorkerInputType.SCHEDULER));
    }

    public Object getRatePerSec() {
        return ratePerSec;
    }

    public void setRatePerSec(Object ratePerSec) {
        this.ratePerSec = ratePerSec;
    }

    public double ratePerSec() {
        return new InputRateParser().parse(ratePerSec, InputRateParser.SCHEDULER_PATH);
    }

    public Object getMaxMessages() {
        return maxMessages;
    }

    public void setMaxMessages(Object maxMessages) {
        this.maxMessages = maxMessages;
    }

    public long maxMessages() {
        return new InputScheduleParser().parse(maxMessages, InputScheduleField.MAX_MESSAGES,
            InputScheduleField.MAX_MESSAGES.path(WorkerInputType.SCHEDULER));
    }

    public SchedulerSettings settings() {
        return new SchedulerSettingsParser().parse(rawSettings(), "inputs.scheduler");
    }

    @Override
    public void validateConfigured(String prefix) {
        new SchedulerSettingsParser().parse(rawSettings(), prefix);
    }

    private Map<String, Object> rawSettings() {
        var values = new LinkedHashMap<String, Object>();
        values.put(InputRateParser.FIELD, ratePerSec);
        values.put(InputScheduleField.INITIAL_DELAY_MS.key(), initialDelayMs);
        values.put(InputScheduleField.TICK_INTERVAL_MS.key(), tickIntervalMs);
        values.put(InputScheduleField.MAX_PENDING_TICKS.key(), maxPendingTicks);
        values.put(InputScheduleField.MAX_MESSAGES.key(), maxMessages);
        return values;
    }
}
