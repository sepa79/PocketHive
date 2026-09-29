package io.pockethive.swarmcontroller.config;

import io.pockethive.observability.metrics.PocketHiveMetricsAdapter;
import io.pockethive.sink.clickhouse.metrics.ClickHouseMetricsSinkProperties;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.util.Objects;
import org.springframework.boot.context.properties.bind.Name;
import org.springframework.validation.annotation.Validated;

/**
 * Responsibility: bind and validate Controller product-metrics settings.
 * Must not: duplicate ClickHouse defaults, export ENV fields or publish metrics.
 * Contract: RESP-CLICKHOUSE-ENVIRONMENT — docs/architecture/runtime-responsibilities.md#resp-clickhouse-environment.
 */
@Validated
public final class SwarmControllerMetricsProperties {
    private final PocketHiveMetricsAdapter adapter;
    private final Duration publishInterval;
    private final @Valid ClickHouseMetricsSinkProperties clickHouse;

    public SwarmControllerMetricsProperties(@NotNull PocketHiveMetricsAdapter adapter,
                   @NotNull Duration publishInterval,
                   @Name("clickhouse") @Valid ClickHouseMetricsSinkProperties clickHouse) {
        this.adapter = Objects.requireNonNull(adapter, "adapter");
        this.publishInterval = Objects.requireNonNull(publishInterval, "publishInterval");
        this.clickHouse = clickHouse == null ? ClickHouseMetricsSinkProperties.disabled() : clickHouse;
        if (this.publishInterval.isZero() || this.publishInterval.isNegative()) {
            throw new IllegalArgumentException("metrics.publishInterval must be positive");
        }
        if (this.adapter == PocketHiveMetricsAdapter.CLICKHOUSE) {
            this.clickHouse.requireConfigured();
        }
    }

    public PocketHiveMetricsAdapter adapter() {
        return adapter;
    }

    public Duration publishInterval() {
        return publishInterval;
    }

    public ClickHouseMetricsSinkProperties clickHouse() {
        return clickHouse;
    }
}
