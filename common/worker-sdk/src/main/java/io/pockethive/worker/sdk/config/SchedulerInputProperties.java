package io.pockethive.worker.sdk.config;

import io.pockethive.work.config.input.InputRateParser;

/**
 * Responsibility: bind scheduler startup settings and delegate rate validation to work-config.
 * Must not: implement input-rate constraints or schedule work.
 * Contract: RESP-WORK-IO-CONFIG — docs/architecture/runtime-responsibilities.md#resp-work-io-config.
 * Consumes RESP-WORK-INPUT-RATE; timing and limit settings remain B02 debt.
 */
public class SchedulerInputProperties implements WorkInputConfig {

    private static final long MIN_MAX_MESSAGES = 0L;

    private boolean enabled = false;
    private long initialDelayMs = 0L;
    private long tickIntervalMs = 1_000L;
    private int maxPendingTicks = 1;
    private Object ratePerSec;
    /**
     * Optional upper bound on the total number of messages the scheduler will
     * dispatch for the current configuration. A value of {@code 0} means
     * "no limit" (infinite run).
     */
    private Long maxMessages;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public long getInitialDelayMs() {
        return initialDelayMs;
    }

    public void setInitialDelayMs(long initialDelayMs) {
        this.initialDelayMs = Math.max(0L, initialDelayMs);
    }

    public long getTickIntervalMs() {
        return tickIntervalMs;
    }

    public void setTickIntervalMs(long tickIntervalMs) {
        this.tickIntervalMs = Math.max(100L, tickIntervalMs);
    }

    public int getMaxPendingTicks() {
        return maxPendingTicks;
    }

    public void setMaxPendingTicks(int maxPendingTicks) {
        this.maxPendingTicks = Math.max(1, maxPendingTicks);
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

    public long getMaxMessages() {
        return requireMaxMessages(maxMessages, "maxMessages");
    }

    public void setMaxMessages(long maxMessages) {
        this.maxMessages = maxMessages;
    }

    @Override
    public void validateConfigured(String prefix) {
        new InputRateParser().parse(ratePerSec, prefix + "." + InputRateParser.FIELD);
        requireMaxMessages(maxMessages, prefix + ".maxMessages");
    }

    private static long requirePresent(Long value, String name) {
        if (value == null) {
            throw new IllegalStateException(name + " must be configured");
        }
        return value;
    }

    private static long requireMaxMessages(Long value, String name) {
        long limit = requirePresent(value, name);
        if (limit < MIN_MAX_MESSAGES) {
            throw new IllegalStateException(name + " must be >= " + MIN_MAX_MESSAGES);
        }
        return limit;
    }
}
