package io.pockethive.orchestrator.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "pockethive.control-plane")
public class OrchestratorProperties {

    private final Orchestrator orchestrator;

    public OrchestratorProperties(@Valid Orchestrator orchestrator) {
        this.orchestrator = Objects.requireNonNull(orchestrator, "orchestrator");
    }

    public String getControlQueuePrefix() {
        return orchestrator.controlQueuePrefix();
    }

    public String getStatusQueuePrefix() {
        return orchestrator.statusQueuePrefix();
    }

    public Rabbit getRabbit() {
        return orchestrator.rabbit();
    }

    public Metrics getMetrics() {
        return orchestrator.metrics();
    }

    public Docker getDocker() {
        return orchestrator.docker();
    }

    public String getImageRepositoryPrefix() {
        Images images = orchestrator.images();
        if (images == null) {
            return null;
        }
        String prefix = images.getRepositoryPrefix();
        if (prefix == null || prefix.isBlank()) {
            return null;
        }
        return prefix;
    }

    public ScenarioManager getScenarioManager() {
        return orchestrator.scenarioManager();
    }

    @Validated
    public static final class Orchestrator {

        private final String controlQueuePrefix;
        private final String statusQueuePrefix;
        private final @Valid Rabbit rabbit;
        private final @Valid Metrics metrics;
        private final @Valid Docker docker;
        private final @Valid Images images;
        private final @Valid ScenarioManager scenarioManager;

        public Orchestrator(@NotBlank String controlQueuePrefix,
                             @NotBlank String statusQueuePrefix,
                             @Valid Rabbit rabbit,
                             @Valid Metrics metrics,
                             @Valid Docker docker,
                             @Valid Images images,
                             @Valid ScenarioManager scenarioManager) {
            this.controlQueuePrefix = requireNonBlank(controlQueuePrefix, "controlQueuePrefix");
            this.statusQueuePrefix = requireNonBlank(statusQueuePrefix, "statusQueuePrefix");
            this.rabbit = Objects.requireNonNull(rabbit, "rabbit");
            this.metrics = Objects.requireNonNull(metrics, "metrics");
            this.docker = Objects.requireNonNull(docker, "docker");
            this.images = Objects.requireNonNull(images, "images");
            this.scenarioManager = Objects.requireNonNull(scenarioManager, "scenarioManager");
        }

        public String controlQueuePrefix() {
            return controlQueuePrefix;
        }

        public String statusQueuePrefix() {
            return statusQueuePrefix;
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

        public Images images() {
            return images;
        }

        public ScenarioManager scenarioManager() {
            return scenarioManager;
        }
    }

    @Validated
    public static final class Rabbit {

        private final String logsExchange;
        private final @Valid Logging logging;

        public Rabbit(@NotBlank String logsExchange, @Valid Logging logging) {
            this.logsExchange = requireNonBlank(logsExchange, "logsExchange");
            this.logging = Objects.requireNonNull(logging, "logging");
        }

        public String getLogsExchange() {
            return logsExchange;
        }

        public Logging getLogging() {
            return logging;
        }
    }

    @Validated
    public static final class Logging {

        private final boolean enabled;

        public Logging(@NotNull Boolean enabled) {
            this.enabled = Objects.requireNonNull(enabled, "enabled");
        }

        public boolean isEnabled() {
            return enabled;
        }
    }

    @Validated
    public static final class Metrics {

        private final @Valid Pushgateway pushgateway;

        public Metrics(@Valid Pushgateway pushgateway) {
            this.pushgateway = Objects.requireNonNull(pushgateway, "pushgateway");
        }

        public Pushgateway getPushgateway() {
            return pushgateway;
        }
    }

    @Validated
    public static final class Pushgateway {

        private final boolean enabled;
        private final String baseUrl;
        private final Duration pushRate;
        private final String shutdownOperation;
        private final String job;
        private final @Valid GroupingKey groupingKey;

        public Pushgateway(@NotNull Boolean enabled,
                           @NotBlank String baseUrl,
                           @NotNull Duration pushRate,
                           @NotBlank String shutdownOperation,
                           @NotBlank String job,
                           @Valid GroupingKey groupingKey) {
            this.enabled = Objects.requireNonNull(enabled, "enabled");
            this.baseUrl = requireNonBlank(baseUrl, "baseUrl");
            this.pushRate = Objects.requireNonNull(pushRate, "pushRate");
            this.shutdownOperation = requireNonBlank(shutdownOperation, "shutdownOperation");
            this.job = requireNonBlank(job, "job");
            this.groupingKey = Objects.requireNonNull(groupingKey, "groupingKey");
        }

        public boolean isEnabled() {
            return enabled;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public Duration getPushRate() {
            return pushRate;
        }

        public String getShutdownOperation() {
            return shutdownOperation;
        }

        public String getJob() {
            return job;
        }

        public GroupingKey getGroupingKey() {
            return groupingKey;
        }
    }

    @Validated
    public static final class GroupingKey {

        private final String instance;

        public GroupingKey(@NotBlank String instance) {
            this.instance = requireNonBlank(instance, "instance");
        }

        public String getInstance() {
            return instance;
        }
    }

    @Validated
    public static final class Docker {

        private final String socketPath;

        public Docker(@NotBlank String socketPath) {
            this.socketPath = requireNonBlank(socketPath, "socketPath");
        }

        public String getSocketPath() {
            return socketPath;
        }
    }

    @Validated
    public static final class Images {

        private final String repositoryPrefix;

        public Images(String repositoryPrefix) {
            if (repositoryPrefix == null || repositoryPrefix.isBlank()) {
                this.repositoryPrefix = null;
            } else {
                String trimmed = repositoryPrefix.trim();
                // Normalise by stripping trailing slashes so callers can safely append "/name"
                while (trimmed.endsWith("/")) {
                    trimmed = trimmed.substring(0, trimmed.length() - 1);
                }
                this.repositoryPrefix = trimmed.isEmpty() ? null : trimmed;
            }
        }

        public String getRepositoryPrefix() {
            return repositoryPrefix;
        }
    }

    @Validated
    public static final class ScenarioManager {

        private final String url;
        private final @Valid Http http;

        public ScenarioManager(@NotBlank String url, @Valid Http http) {
            this.url = requireNonBlank(url, "url");
            this.http = Objects.requireNonNull(http, "http");
        }

        public String getUrl() {
            return url;
        }

        public Http getHttp() {
            return http;
        }
    }

    @Validated
    public static final class Http {

        private final Duration connectTimeout;
        private final Duration readTimeout;

        public Http(@NotNull Duration connectTimeout, @NotNull Duration readTimeout) {
            this.connectTimeout = Objects.requireNonNull(connectTimeout, "connectTimeout");
            this.readTimeout = Objects.requireNonNull(readTimeout, "readTimeout");
        }

        public Duration getConnectTimeout() {
            return connectTimeout;
        }

        public Duration getReadTimeout() {
            return readTimeout;
        }
    }

    private static String requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
