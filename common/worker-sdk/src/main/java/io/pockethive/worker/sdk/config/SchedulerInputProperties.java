package io.pockethive.worker.sdk.config;

/**
 * Scheduler-specific tuning parameters that can be scoped per worker role.
 */
public class SchedulerInputProperties implements WorkInputConfig {

    private boolean enabled = false;
    private long initialDelayMs = 0L;
    private long tickIntervalMs = 1_000L;
    private int maxPendingTicks = 1;
    private double ratePerSec = 0.0;
    /**
     * Optional upper bound on the total number of messages the scheduler will
     * dispatch for the current configuration. A value of {@code 0} means
     * "no limit" (infinite run).
     */
    private long maxMessages = 0L;

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
        return ratePerSec;
    }

    public void setRatePerSec(double ratePerSec) {
        this.ratePerSec = Math.max(0.0, ratePerSec);
    }

    public long getMaxMessages() {
        return maxMessages;
    }

    public void setMaxMessages(long maxMessages) {
        this.maxMessages = Math.max(0L, maxMessages);
    }
}
