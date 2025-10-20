package io.pockethive.swarmcontroller.config;

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
    private final String controlQueuePrefix;
    private final Traffic traffic;
    private final Rabbit rabbit;
    private final Metrics metrics;
    private final Docker docker;

    public SwarmControllerProperties(@NotBlank String swarmId,
                                     @NotBlank String exchange,
                                     @Valid Manager manager,
                                     @Valid SwarmController swarmController) {
        this.swarmId = requireNonBlank(swarmId, "swarmId");
        this.role = requireNonBlank(Objects.requireNonNull(manager, "manager").role(), "manager.role");
        this.controlExchange = requireNonBlank(exchange, "exchange");
        SwarmController resolved = Objects.requireNonNull(swarmController, "swarmController");
        this.controlQueuePrefix = requireNonBlank(resolved.controlQueuePrefix(), "controlQueuePrefix");
        this.traffic = Objects.requireNonNull(resolved.traffic(), "traffic");
        this.rabbit = Objects.requireNonNull(resolved.rabbit(), "rabbit");
        this.metrics = Objects.requireNonNull(resolved.metrics(), "metrics");
        this.docker = Objects.requireNonNull(resolved.docker(), "docker");
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

    public Traffic getTraffic() {
        return traffic;
    }

    public Rabbit getRabbit() {
        return rabbit;
    }

    public Metrics getMetrics() {
        return metrics;
    }

    public Docker getDocker() {
        return docker;
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
        private final String controlQueuePrefix;
        private final Traffic traffic;
        private final Rabbit rabbit;
        private final Metrics metrics;
        private final Docker docker;

        public SwarmController(@NotBlank String controlQueuePrefix,
                               @Valid Traffic traffic,
                               @Valid Rabbit rabbit,
                               @Valid Metrics metrics,
                               @Valid Docker docker) {
            this.controlQueuePrefix = requireNonBlank(controlQueuePrefix, "controlQueuePrefix");
            this.traffic = Objects.requireNonNull(traffic, "traffic");
            this.rabbit = Objects.requireNonNull(rabbit, "rabbit");
            this.metrics = Objects.requireNonNull(metrics, "metrics");
            this.docker = Objects.requireNonNull(docker, "docker");
        }

        public String controlQueuePrefix() {
            return controlQueuePrefix;
        }

        public Traffic traffic() {
            return traffic;
        }

        public Rabbit rabbit() {
            return rabbit;
        }

        public Metrics metrics() {
            return metrics;
        }

        public Docker docker() {
            return docker;
        }
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
            String resolvedSuffix = requireNonBlank(suffix, "suffix");
            return queuePrefix + "." + resolvedSuffix;
        }
    }

    @Validated
    public static final class Rabbit {
        private final String logsExchange;

        public Rabbit(@NotBlank String logsExchange) {
            this.logsExchange = requireNonBlank(logsExchange, "logsExchange");
        }

        public String logsExchange() {
            return logsExchange;
        }
    }

    @Validated
    public static final class Metrics {
        private final @Valid Pushgateway pushgateway;

        public Metrics(@Valid Pushgateway pushgateway) {
            this.pushgateway = Objects.requireNonNull(pushgateway, "pushgateway");
        }

        public Pushgateway pushgateway() {
            return pushgateway;
        }
    }

    @Validated
    public static final class Pushgateway {
        private final boolean enabled;
        private final String baseUrl;
        private final Duration pushRate;
        private final String shutdownOperation;

        public Pushgateway(boolean enabled,
                           String baseUrl,
                           @NotNull Duration pushRate,
                           @NotBlank String shutdownOperation) {
            this.enabled = enabled;
            this.baseUrl = baseUrl;
            this.pushRate = Objects.requireNonNull(pushRate, "pushRate");
            this.shutdownOperation = requireNonBlank(shutdownOperation, "shutdownOperation");
        }

        public boolean enabled() {
            return enabled;
        }

        public String baseUrl() {
            return baseUrl;
        }

        public Duration pushRate() {
            return pushRate;
        }

        public String shutdownOperation() {
            return shutdownOperation;
        }

        public boolean hasBaseUrl() {
            return baseUrl != null && !baseUrl.isBlank();
        }
    }

    @Validated
    public static final class Docker {
        private final String host;
        private final String socketPath;

        public Docker(String host, @NotBlank String socketPath) {
            this.host = host;
            this.socketPath = requireNonBlank(socketPath, "socketPath");
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
    }

    private static String requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
