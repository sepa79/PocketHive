package io.pockethive.controlplane.spring;

import io.pockethive.observability.metrics.PocketHiveMetricsAdapter;
import io.pockethive.sink.clickhouse.metrics.ClickHouseMetricsSinkProperties;
import java.time.Duration;
import java.util.Objects;
import static io.pockethive.controlplane.spring.ControlPlaneContainerEnvironmentFactory.requireArgument;

/**
 * Responsibility: retain explicit participant environment settings for the shared Control Plane projection.
 * Must not: carry Work topology, resolve broker fields or perform infrastructure operations.
 * Contract: RESP-RABBIT-CONNECTION — docs/architecture/runtime-responsibilities.md#resp-rabbit-connection.
 */
public record WorkerSettings(String swarmId,
                             String runId,
                             String controlExchange,
                             String controlQueuePrefix,
                             MetricsSettings metrics) {
    public WorkerSettings {
        Objects.requireNonNull(metrics, "metrics");
        requireArgument(swarmId, "swarmId");
        requireArgument(runId, "runId");
        requireArgument(controlExchange, "controlExchange");
        requireArgument(controlQueuePrefix, "controlQueuePrefix");
    }
}
