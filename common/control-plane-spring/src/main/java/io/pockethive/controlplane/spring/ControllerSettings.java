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
public record ControllerSettings(MetricsSettings metrics,
                                 String runId) {
    public ControllerSettings {
        Objects.requireNonNull(metrics, "metrics");
        requireArgument(runId, "runId");
    }

}
