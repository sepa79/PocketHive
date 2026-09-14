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
public record MetricsSettings(PocketHiveMetricsAdapter adapter,
                              Duration publishInterval,
                              ClickHouseMetricsSinkProperties clickHouse) {
    public MetricsSettings {
        Objects.requireNonNull(adapter, "adapter");
        Objects.requireNonNull(publishInterval, "publishInterval");
        clickHouse = clickHouse == null ? ClickHouseMetricsSinkProperties.disabled() : clickHouse;
        if (publishInterval.isZero() || publishInterval.isNegative()) {
            throw new IllegalArgumentException("metrics.publishInterval must be positive");
        }
        if (adapter == PocketHiveMetricsAdapter.CLICKHOUSE) {
            clickHouse.requireConfigured();
        }
    }
}
