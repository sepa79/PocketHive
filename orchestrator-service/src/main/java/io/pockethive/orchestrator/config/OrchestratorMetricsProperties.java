package io.pockethive.orchestrator.config;

import io.pockethive.observability.metrics.PocketHiveMetricsAdapter;
import io.pockethive.sink.clickhouse.metrics.ClickHouseMetricsSinkProperties;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.util.Objects;
import org.springframework.boot.context.properties.bind.Name;
import org.springframework.validation.annotation.Validated;

/**
 * Responsibility: Validate Orchestrator product-metrics settings.
 * Must not: Resolve control-plane topology or provision runtime resources.
 * Contract: RESP-CLICKHOUSE-ENVIRONMENT — docs/architecture/runtime-responsibilities.md#resp-clickhouse-environment.
 */
@Validated
public final class OrchestratorMetricsProperties {

    private final PocketHiveMetricsAdapter adapter;
    private final Duration publishInterval;
    private final @Valid ClickHouseMetricsSinkProperties clickHouse;

    public OrchestratorMetricsProperties(@NotNull PocketHiveMetricsAdapter adapter,
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

    public PocketHiveMetricsAdapter getAdapter() {
        return adapter;
    }

    public Duration getPublishInterval() {
        return publishInterval;
    }

    public ClickHouseMetricsSinkProperties getClickHouse() {
        return clickHouse;
    }
}
