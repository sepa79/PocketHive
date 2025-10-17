package io.pockethive.swarmcontroller.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.time.Duration;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "pockethive.control-plane.swarm-controller")
public class SwarmControllerProperties {

    private final String swarmId;
    private final String role;
    private final String controlExchange;
    private final String controlQueuePrefix;
    private final Traffic traffic;
    private final Rabbit rabbit;
    private final Metrics metrics;
    private final Docker docker;

    public SwarmControllerProperties(
        @DefaultValue("default") @NotBlank String swarmId,
        @DefaultValue("swarm-controller") @NotBlank String role,
        @DefaultValue("ph.control") @NotBlank String controlExchange,
        @DefaultValue("ph.control") @NotBlank String controlQueuePrefix,
        @DefaultValue Traffic traffic,
        @DefaultValue Rabbit rabbit,
        @DefaultValue Metrics metrics,
        @DefaultValue Docker docker) {
        this.swarmId = requireNonBlank(swarmId, "swarmId");
        this.role = requireNonBlank(role, "role");
        this.controlExchange = requireNonBlank(controlExchange, "controlExchange");
        this.controlQueuePrefix = requireNonBlank(controlQueuePrefix, "controlQueuePrefix");
        this.traffic = Objects.requireNonNullElseGet(traffic, () -> new Traffic(null, null))
            .withDefaults(this.swarmId);
        this.rabbit = Objects.requireNonNullElseGet(rabbit, () -> new Rabbit(null, null)).withDefaults();
        this.metrics = Objects.requireNonNullElseGet(metrics, () -> new Metrics(null)).withDefaults();
        this.docker = Objects.requireNonNullElseGet(docker, () -> new Docker(null, null)).withDefaults();
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
        return traffic.queueName(requireNonBlank(suffix, "suffix"));
    }

    public String controlQueueName(String instanceId) {
        return controlQueueName(role, instanceId);
    }

    public String controlQueueName(String role, String instanceId) {
        String resolvedRole = requireNonBlank(role, "role");
        String resolvedInstance = requireNonBlank(instanceId, "instanceId");
        return controlQueuePrefix + "." + resolvedRole + "." + resolvedInstance;
    }

    private static String requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    @Validated
    public static final class Traffic {
        private final String hiveExchange;
        private final String queuePrefix;

        public Traffic(String hiveExchange, String queuePrefix) {
            this.hiveExchange = hiveExchange;
            this.queuePrefix = queuePrefix;
        }

        private Traffic withDefaults(String swarmId) {
            String prefix = normalisePrefix(defaultIfBlank(queuePrefix, "ph." + swarmId));
            String exchange = defaultIfBlank(hiveExchange, prefix + ".hive");
            return new Traffic(exchange, prefix);
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
        private final String host;
        private final String logsExchange;

        public Rabbit(String host, String logsExchange) {
            this.host = host;
            this.logsExchange = logsExchange;
        }

        private Rabbit withDefaults() {
            String resolvedHost = defaultIfBlank(host, "rabbitmq");
            String resolvedLogs = defaultIfBlank(logsExchange, "ph.logs");
            return new Rabbit(resolvedHost, resolvedLogs);
        }

        public String host() {
            return host;
        }

        public String logsExchange() {
            return logsExchange;
        }
    }

    @Validated
    public static final class Metrics {
        private final @Valid Pushgateway pushgateway;

        public Metrics(@DefaultValue Pushgateway pushgateway) {
            this.pushgateway = pushgateway == null ? Pushgateway.disabled() : pushgateway;
        }

        private Metrics withDefaults() {
            return new Metrics(pushgateway.withDefaults());
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

        public Pushgateway(
            @DefaultValue("false") boolean enabled,
            String baseUrl,
            @DefaultValue("PT1M") Duration pushRate,
            String shutdownOperation) {
            this.enabled = enabled;
            this.baseUrl = baseUrl;
            this.pushRate = pushRate;
            this.shutdownOperation = shutdownOperation;
        }

        private static Pushgateway disabled() {
            return new Pushgateway(false, null, Duration.ofMinutes(1), "DELETE");
        }

        private Pushgateway withDefaults() {
            Duration rate = pushRate != null ? pushRate : Duration.ofMinutes(1);
            String operation = defaultIfBlank(shutdownOperation, "DELETE");
            return new Pushgateway(enabled, baseUrl, rate, operation);
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

        public Docker(String host, String socketPath) {
            this.host = host;
            this.socketPath = socketPath;
        }

        private Docker withDefaults() {
            String resolvedSocket = defaultIfBlank(socketPath, "/var/run/docker.sock");
            return new Docker(host, resolvedSocket);
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

    private static String defaultIfBlank(String value, String defaultValue) {
        return (value == null || value.isBlank()) ? defaultValue : value;
    }

    private static String normalisePrefix(String value) {
        String trimmed = requireNonBlank(value, "queuePrefix");
        if (trimmed.endsWith(".")) {
            return trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }
}
