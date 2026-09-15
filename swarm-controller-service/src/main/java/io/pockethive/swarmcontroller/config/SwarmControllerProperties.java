package io.pockethive.swarmcontroller.config;

import io.pockethive.rabbit.api.RabbitResourceNames;

import io.pockethive.manager.runtime.ComputeAdapterType;
import io.pockethive.observability.metrics.PocketHiveMetricsAdapter;
import io.pockethive.sink.clickhouse.metrics.ClickHouseMetricsSinkProperties;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Responsibility: bind explicit Controller control, metrics, compute and feature settings.
 * Must not: require Work adapter settings, own resource names or perform runtime effects.
 * Contract: RESP-WORK-RESOURCE-NAMES — docs/architecture/runtime-responsibilities.md#resp-work-resource-names.
 */
@Validated
@ConfigurationProperties(prefix = "pockethive.control-plane")
public class SwarmControllerProperties {

    private final String swarmId;
    private final String role;
    private final String controlExchange;
    private final String controlQueuePrefixBase;
    private final Metrics metrics;
    private final Docker docker;
    private final Features features;

    public SwarmControllerProperties(@NotBlank String swarmId,
                                     @NotBlank String exchange,
                                     @NotBlank String controlQueuePrefix,
                                     @Valid Manager manager,
                                     @Valid SwarmController swarmController) {
        this.swarmId = requireNonBlank(swarmId, "swarmId");
        this.role = requireNonBlank(Objects.requireNonNull(manager, "manager").role(), "manager.role");
        this.controlExchange = requireNonBlank(exchange, "exchange");
        this.controlQueuePrefixBase = requireNonBlank(controlQueuePrefix, "controlQueuePrefix");
        SwarmController resolved = Objects.requireNonNull(swarmController, "swarmController");
        this.metrics = Objects.requireNonNull(resolved.metrics(), "metrics");
        this.docker = Objects.requireNonNull(resolved.docker(), "docker");
        this.features = Objects.requireNonNull(resolved.features(), "features");
    }

    public String getSwarmId() {
        return swarmId;
    }

    public String getRole() {
        return role;
    }

    public String getControlExchange() {
        return controlExchange;
    }

    public String getControlQueuePrefixBase() {
        return controlQueuePrefixBase;
    }

    public Metrics getMetrics() {
        return metrics;
    }

    public Docker getDocker() {
        return docker;
    }

    public Features getFeatures() {
        return features;
    }




    public String controlQueueName(String instanceId) {
        return new RabbitResourceNames().swarmControllerQueue(
            controlQueuePrefixBase, swarmId, role, requireNonBlank(instanceId, "instanceId"));
    }

    public String controlQueueName(String role, String instanceId) {
        String resolvedRole = requireNonBlank(role, "role");
        String resolvedInstance = requireNonBlank(instanceId, "instanceId");
        return new RabbitResourceNames().workerControlQueue(
            controlQueuePrefixBase, swarmId, resolvedRole, resolvedInstance);
    }

    @Validated
    public static final class Manager {
        private final String role;

        public Manager(@NotBlank String role) {
            this.role = requireNonBlank(role, "role");
        }

        public String role() {
            return role;
        }
    }

    @Validated
    public static final class SwarmController {
            private final Metrics metrics;
        private final Docker docker;
        private final Features features;

        public SwarmController(@Valid Metrics metrics,
                               @Valid Docker docker,
                               @Valid Features features) {
            this.metrics = Objects.requireNonNull(metrics, "metrics");
            this.docker = Objects.requireNonNull(docker, "docker");
            this.features = features != null ? features : new Features(null);
        }

        public Metrics metrics() {
            return metrics;
        }

        public Docker docker() {
            return docker;
        }

        public Features features() {
            return features;
        }
    }

    @Validated
    public static final class Metrics {
        private final PocketHiveMetricsAdapter adapter;
        private final Duration publishInterval;
        private final @Valid ClickHouseMetricsSinkProperties clickHouse;

        public Metrics(@NotNull PocketHiveMetricsAdapter adapter,
                       @NotNull Duration publishInterval,
                       @Valid ClickHouseMetricsSinkProperties clickHouse) {
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

    @Validated
    public static final class Docker {
        private final String host;
        private final String socketPath;
        private final ComputeAdapterType computeAdapter;

        public Docker(String host, @NotBlank String socketPath, ComputeAdapterType computeAdapter) {
            this.host = host;
            this.socketPath = requireNonBlank(socketPath, "socketPath");
            this.computeAdapter = ComputeAdapterType.defaulted(computeAdapter);
        }

        public String host() {
            return host;
        }

        public String socketPath() {
            return socketPath;
        }

        public boolean hasHost() {
            return host != null && !host.isBlank();
        }

        public ComputeAdapterType computeAdapter() {
            return computeAdapter;
        }
    }

    @Validated
    public static final class Features {
        private final boolean bufferGuardEnabled;

        public Features(Boolean bufferGuardEnabled) {
            this.bufferGuardEnabled = Boolean.TRUE.equals(bufferGuardEnabled);
        }

        public boolean bufferGuardEnabled() {
            return bufferGuardEnabled;
        }
    }

    private static String requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

}
