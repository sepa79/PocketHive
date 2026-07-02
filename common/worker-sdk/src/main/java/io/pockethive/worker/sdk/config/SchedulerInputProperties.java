package io.pockethive.worker.sdk.config;

/**
 * Scheduler-specific tuning parameters that can be scoped per worker role.
 */
public class SchedulerInputProperties implements WorkInputConfig {

    private static final double MIN_RATE_PER_SEC = 0.0;
    private static final long MIN_MAX_MESSAGES = 0L;

    private boolean enabled = false;
    private long initialDelayMs = 0L;
    private long tickIntervalMs = 1_000L;
    private int maxPendingTicks = 1;
    private Double ratePerSec;
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

    public double getRatePerSec() {
        return requireRatePerSec(ratePerSec, "ratePerSec");
    }

    public void setRatePerSec(double ratePerSec) {
        this.ratePerSec = ratePerSec;
    }

    public long getMaxMessages() {
        return requireMaxMessages(maxMessages, "maxMessages");
    }

    public void setMaxMessages(long maxMessages) {
        this.maxMessages = maxMessages;
    }

    @Override
    public void validateConfigured(String prefix) {
        requireRatePerSec(ratePerSec, prefix + ".ratePerSec");
        requireMaxMessages(maxMessages, prefix + ".maxMessages");
    }

    private static double requirePresent(Double value, String name) {
        if (value == null) {
            throw new IllegalStateException(name + " must be configured");
        }
        return value;
    }

    private static long requirePresent(Long value, String name) {
        if (value == null) {
            throw new IllegalStateException(name + " must be configured");
        }
        return value;
    }

    private static double requireRatePerSec(Double value, String name) {
        double rate = requirePresent(value, name);
        if (!Double.isFinite(rate) || rate < MIN_RATE_PER_SEC) {
            throw new IllegalStateException(name + " must be >= " + MIN_RATE_PER_SEC);
        }
        return rate;
    }

    private static long requireMaxMessages(Long value, String name) {
        long limit = requirePresent(value, name);
        if (limit < MIN_MAX_MESSAGES) {
            throw new IllegalStateException(name + " must be >= " + MIN_MAX_MESSAGES);
        }
        return limit;
    }
}
