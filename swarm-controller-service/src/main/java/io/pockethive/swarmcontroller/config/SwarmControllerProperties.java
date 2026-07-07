package io.pockethive.swarmcontroller.config;

import io.pockethive.controlplane.spring.ControlPlaneContainerEnvironmentFactory;
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

@Validated
@ConfigurationProperties(prefix = "pockethive.control-plane")
public class SwarmControllerProperties {

    private final String swarmId;
    private final String role;
    private final String controlExchange;
    private final String controlQueuePrefixBase;
    private final String controlQueuePrefix;
    private final Traffic traffic;
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
        this.controlQueuePrefix = normalizeControlQueuePrefix(this.swarmId, this.controlQueuePrefixBase);
        SwarmController resolved = Objects.requireNonNull(swarmController, "swarmController");
        this.traffic = Objects.requireNonNull(resolved.traffic(), "traffic");
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

    public String getControlQueuePrefix() {
        return controlQueuePrefix;
    }

    public String getControlQueuePrefixBase() {
        return controlQueuePrefixBase;
    }

    public Traffic getTraffic() {
        return traffic;
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

    public String hiveExchange() {
        return traffic.hiveExchange();
    }

    public String queueName(String suffix) {
        return traffic.queueName(suffix);
    }

    public String controlQueueName(String instanceId) {
        return controlQueueName(role, instanceId);
    }

    public String controlQueueName(String role, String instanceId) {
        String resolvedRole = requireNonBlank(role, "role");
        String resolvedInstance = requireNonBlank(instanceId, "instanceId");
        return controlQueuePrefix + "." + resolvedRole + "." + resolvedInstance;
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
        private final Traffic traffic;
        private final Metrics metrics;
        private final Docker docker;
        private final Features features;

        public SwarmController(@Valid Traffic traffic,
                               @Valid Metrics metrics,
                               @Valid Docker docker,
                               @Valid Features features) {
            this.traffic = Objects.requireNonNull(traffic, "traffic");
            this.metrics = Objects.requireNonNull(metrics, "metrics");
            this.docker = Objects.requireNonNull(docker, "docker");
            this.features = features != null ? features : new Features(null);
        }

        public Traffic traffic() {
            return traffic;
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

    private static String normalizeControlQueuePrefix(String swarmId, String prefix) {
        String resolvedSwarmId = requireNonBlank(swarmId, "swarmId");
        String normalized = requireNonBlank(prefix, "controlQueuePrefix");
        if (normalized.endsWith("." + resolvedSwarmId) || normalized.contains("." + resolvedSwarmId + ".")) {
            return normalized;
        }
        if (normalized.endsWith(".")) {
            return normalized + resolvedSwarmId;
        }
        return normalized + "." + resolvedSwarmId;
    }

    @Validated
    public static final class Traffic {
        private final String hiveExchange;
        private final String queuePrefix;

        public Traffic(@NotBlank String hiveExchange, @NotBlank String queuePrefix) {
            this.hiveExchange = requireNonBlank(hiveExchange, "hiveExchange");
            this.queuePrefix = requireNonBlank(queuePrefix, "queuePrefix");
        }

        public String hiveExchange() {
            return hiveExchange;
        }

        public String queuePrefix() {
            return queuePrefix;
        }

        public String queueName(String suffix) {
            return ControlPlaneContainerEnvironmentFactory.swarmTrafficQueueName(queuePrefix, suffix);
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
