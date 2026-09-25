package io.pockethive.swarmcontroller.config;

import io.pockethive.rabbit.api.RabbitResourceNames;

import io.pockethive.manager.runtime.ComputeAdapterType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
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
    private final SwarmControllerMetricsProperties metrics;
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

    public SwarmControllerMetricsProperties getMetrics() {
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
        private final SwarmControllerMetricsProperties metrics;
        private final Docker docker;
        private final Features features;

        public SwarmController(@Valid SwarmControllerMetricsProperties metrics,
                               @Valid Docker docker,
                               @Valid Features features) {
            this.metrics = Objects.requireNonNull(metrics, "metrics");
            this.docker = Objects.requireNonNull(docker, "docker");
            this.features = features != null ? features : new Features(null);
        }

        public SwarmControllerMetricsProperties metrics() {
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
