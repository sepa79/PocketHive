package io.pockethive.work.config.scheduler;

/**
 * Responsibility: retain immutable scheduler settings validated by SchedulerSettingsParser.
 * Must not: parse declarations, run timers or own worker state.
 * Contract: RESP-WORK-SCHEDULER-SETTINGS — docs/architecture/runtime-responsibilities.md#resp-work-scheduler-settings.
 */
public final class SchedulerSettings {
    private final double ratePerSec;
    private final long initialDelayMs;
    private final long tickIntervalMs;
    private final int maxPendingTicks;
    private final long maxMessages;

    SchedulerSettings(double ratePerSec, long initialDelayMs, long tickIntervalMs, int maxPendingTicks, long maxMessages) {
        this.ratePerSec = ratePerSec;
        this.initialDelayMs = initialDelayMs;
        this.tickIntervalMs = tickIntervalMs;
        this.maxPendingTicks = maxPendingTicks;
        this.maxMessages = maxMessages;
    }

    public double ratePerSec() { return ratePerSec; }
    public long initialDelayMs() { return initialDelayMs; }
    public long tickIntervalMs() { return tickIntervalMs; }
    public int maxPendingTicks() { return maxPendingTicks; }
    public long maxMessages() { return maxMessages; }
}
