package io.pockethive.worker.sdk.runtime;

import java.time.Duration;
import java.util.Objects;

/**
 * Configuration properties controlling the cadence of status delta emissions for worker runtimes.
 */
public class WorkerStatusSchedulerProperties {

    private Duration deltaInterval = Duration.ofSeconds(5);

    public Duration getDeltaInterval() {
        return deltaInterval;
    }

    public void setDeltaInterval(Duration deltaInterval) {
        this.deltaInterval = Objects.requireNonNull(deltaInterval, "deltaInterval");
    }
}
