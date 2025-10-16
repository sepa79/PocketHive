package io.pockethive.worker.sdk.runtime;

import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Periodically emits worker status deltas via the {@link WorkerControlPlaneRuntime}.
 */
public class WorkerStatusScheduler {

    private static final Logger log = LoggerFactory.getLogger(WorkerStatusScheduler.class);

    private final WorkerControlPlaneRuntime controlPlaneRuntime;
    private final WorkerStatusSchedulerProperties properties;

    public WorkerStatusScheduler(
        WorkerControlPlaneRuntime controlPlaneRuntime,
        WorkerStatusSchedulerProperties properties
    ) {
        this.controlPlaneRuntime = Objects.requireNonNull(controlPlaneRuntime, "controlPlaneRuntime");
        this.properties = Objects.requireNonNull(properties, "properties");
    }

    @Scheduled(fixedRateString = "#{@workerStatusSchedulerProperties.deltaInterval.toMillis()}")
    public void emitStatusDelta() {
        if (log.isTraceEnabled()) {
            log.trace("Emitting scheduled worker status delta (interval={}ms)",
                properties.getDeltaInterval().toMillis());
        }
        controlPlaneRuntime.emitStatusDelta();
    }
}
