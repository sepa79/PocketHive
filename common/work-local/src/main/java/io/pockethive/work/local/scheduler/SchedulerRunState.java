package io.pockethive.work.local.scheduler;

import io.pockethive.work.config.input.InputRateParser;
import io.pockethive.work.config.input.InputScheduleField;
import io.pockethive.work.config.input.InputScheduleParser;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;

/**
 * Responsibility: project scheduler controls and own finite-run dispatch accounting.
 * Must not: own accepted worker configuration, schedule ticks or dispatch work.
 * Contract: RESP-WORK-SCHEDULER-RUN — docs/architecture/runtime-responsibilities.md#resp-work-scheduler-run.
 */
public final class SchedulerRunState {
    private final String inputName;
    private final Logger log;
    private volatile double ratePerSec;
    private volatile long maxMessages;
    private final AtomicLong dispatchedCount = new AtomicLong();

    public SchedulerRunState(SchedulerSettings settings, String inputName, Logger log) {
        Objects.requireNonNull(settings, "settings");
        this.inputName = Objects.requireNonNull(inputName, "inputName");
        this.log = Objects.requireNonNull(log, "log");
        this.ratePerSec = settings.ratePerSec();
        this.maxMessages = settings.maxMessages();
    }

    public double ratePerSec() { return ratePerSec; }
    public long maxMessages() { return maxMessages; }
    public long dispatchedCount() { return dispatchedCount.get(); }

    /** Clip a positive policy quota using the limit captured at the start of this tick. */
    public int limitQuota(int quota, long limit) {
        if (limit <= 0L) return quota;
        long remaining = remaining(limit, dispatchedCount.get());
        return quota > remaining ? (int) remaining : quota;
    }

    /** Count after seed creation, before dispatch; -1 means the current run is unlimited. */
    public long recordDispatch() {
        long limit = maxMessages;
        long after = dispatchedCount.incrementAndGet();
        return limit > 0L ? remaining(limit, after) : -1L;
    }

    /** Read-only status projection using the limit captured for the reporting tick. */
    public Map<String, Object> diagnostics(long limit) {
        long dispatched = dispatchedCount.get();
        long remaining = limit > 0L ? remaining(limit, dispatched) : -1L;
        var data = new LinkedHashMap<String, Object>();
        data.put("ratePerSec", ratePerSec);
        data.put("maxMessages", limit);
        data.put("dispatched", dispatched);
        if (remaining >= 0L) data.put("remaining", remaining);
        data.put("exhausted", limit > 0L && remaining == 0L);
        return data;
    }

    private static long remaining(long limit, long dispatched) {
        return Math.max(0L, limit - dispatched);
    }

    public void applyControls(Map<String, Object> rawConfig) {
        if (rawConfig == null || rawConfig.isEmpty()) {
            return;
        }
        Object inputs = rawConfig.get("inputs");
        if (!(inputs instanceof Map<?, ?> inputsMap)) {
            return;
        }
        Object scheduler = inputsMap.get("scheduler");
        if (!(scheduler instanceof Map<?, ?> schedulerMap)) {
            return;
        }

        double rate = schedulerMap.containsKey(InputRateParser.FIELD)
            ? new InputRateParser().parse(schedulerMap.get(InputRateParser.FIELD), InputRateParser.SCHEDULER_PATH)
            : ratePerSec;
        long currentMax = maxMessages;
        long newMax = schedulerMap.containsKey(InputScheduleField.MAX_MESSAGES.key())
            ? new InputScheduleParser().parse(schedulerMap.get(InputScheduleField.MAX_MESSAGES.key()),
                InputScheduleField.MAX_MESSAGES, InputScheduleParser.SCHEDULER_MAX_MESSAGES_PATH)
            : currentMax;
        boolean explicitReset = schedulerMap.containsKey(SchedulerResetParser.FIELD)
            && new SchedulerResetParser().parse(schedulerMap.get(SchedulerResetParser.FIELD), SchedulerResetParser.PATH);

        // Validate all requested scheduler controls before changing settings or counters.
        if (rate != ratePerSec) {
            ratePerSec = rate;
            log.info("{} scheduler ratePerSec updated via config: {}", inputName, rate);
        }
        if (newMax != currentMax) {
            maxMessages = newMax;
            log.info("{} scheduler maxMessages updated via config: {} (previous={})",
                inputName, newMax, currentMax);
        }
        if (newMax != currentMax || explicitReset) {
            long before = dispatchedCount.getAndSet(0L);
            if (log.isInfoEnabled()) {
                log.info(
                    "{} scheduler finite-run counters reset via config (previousDispatched={})",
                    inputName, before);
            }
        }
    }

}
