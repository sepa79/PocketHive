package io.pockethive.orchestrator.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "pockethive.control-plane.orchestrator")
public class OrchestratorProperties {

    private final String controlQueuePrefix;
    private final String statusQueuePrefix;
    private final @Valid Rabbit rabbit;
    private final @Valid Pushgateway pushgateway;
    private final @Valid Docker docker;
    private final @Valid ScenarioManager scenarioManager;

    public OrchestratorProperties(
        @DefaultValue("ph.control.orchestrator") @NotBlank String controlQueuePrefix,
        @DefaultValue("ph.control.orchestrator-status") @NotBlank String statusQueuePrefix,
        @DefaultValue Rabbit rabbit,
        @DefaultValue Pushgateway pushgateway,
        @DefaultValue Docker docker,
        @DefaultValue ScenarioManager scenarioManager) {
        this.controlQueuePrefix = controlQueuePrefix;
        this.statusQueuePrefix = statusQueuePrefix;
        this.rabbit = rabbit;
        this.pushgateway = pushgateway;
        this.docker = docker;
        this.scenarioManager = scenarioManager;
    }

    public String getControlQueuePrefix() {
        return controlQueuePrefix;
    }

    public String getStatusQueuePrefix() {
        return statusQueuePrefix;
    }

    public Rabbit getRabbit() {
        return rabbit;
    }

    public Pushgateway getPushgateway() {
        return pushgateway;
    }

    public Docker getDocker() {
        return docker;
    }

    public ScenarioManager getScenarioManager() {
        return scenarioManager;
    }

    @Validated
    public static final class Rabbit {

        private final String logsExchange;
        private final @Valid Logging logging;

        public Rabbit(@DefaultValue("ph.logs") @NotBlank String logsExchange, @DefaultValue Logging logging) {
            this.logsExchange = logsExchange;
            this.logging = logging;
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

        public Logging(@DefaultValue("false") boolean enabled) {
            this.enabled = enabled;
        }

        public boolean isEnabled() {
            return enabled;
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

        public boolean hasBaseUrl() {
            return baseUrl != null && !baseUrl.isBlank();
        }
    }

    @Validated
    public static final class Docker {

        private final String socketPath;

        public Docker(@NotBlank String socketPath) {
            this.socketPath = socketPath;
        }

        public String getSocketPath() {
            return socketPath;
        }
    }

    @Validated
    public static final class ScenarioManager {

        private final String url;
        private final @Valid Http http;

        public ScenarioManager(@NotBlank String url, @DefaultValue Http http) {
            this.url = url;
            this.http = http;
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
            this.connectTimeout = connectTimeout;
            this.readTimeout = readTimeout;
        }

        public Duration getConnectTimeout() {
            return connectTimeout;
        }

        public Duration getReadTimeout() {
            return readTimeout;
        }
    }
}
