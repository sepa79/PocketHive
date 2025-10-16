package io.pockethive.worker.sdk.runtime;

import java.time.Duration;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties controlling the cadence of status delta emissions for worker runtimes.
 */
@ConfigurationProperties(prefix = "pockethive.worker.status")
public class WorkerStatusSchedulerProperties {

    private Duration deltaInterval = Duration.ofSeconds(5);

    public Duration getDeltaInterval() {
        return deltaInterval;
    }

    public void setDeltaInterval(Duration deltaInterval) {
        this.deltaInterval = Objects.requireNonNull(deltaInterval, "deltaInterval");
    }
}
