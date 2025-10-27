package io.pockethive.scenarios;

import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "pockethive.scenario-manager")
public class ScenarioManagerProperties {
    private final Orchestrator orchestrator = new Orchestrator();
    private final Capabilities capabilities = new Capabilities();

    public Orchestrator orchestrator() {
        return orchestrator;
    }

    public Capabilities capabilities() {
        return capabilities;
    }

    public static class Orchestrator {
        @NotNull
        private URI baseUrl = URI.create("http://orchestrator-service:8080");

        @NotNull
        private Duration connectTimeout = Duration.ofSeconds(2);

        @NotNull
        private Duration readTimeout = Duration.ofSeconds(5);

        public URI getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(URI baseUrl) {
            this.baseUrl = baseUrl;
        }

        public Duration getConnectTimeout() {
            return connectTimeout;
        }

        public void setConnectTimeout(Duration connectTimeout) {
            this.connectTimeout = connectTimeout;
        }

        public Duration getReadTimeout() {
            return readTimeout;
        }

        public void setReadTimeout(Duration readTimeout) {
            this.readTimeout = readTimeout;
        }
    }

    public static class Capabilities {
        @NotNull
        private Duration cacheTtl = Duration.ofSeconds(5);

        private Path offlinePackPath;

        private String offlineSwarmId = "offline-pack";

        public Duration getCacheTtl() {
            return cacheTtl;
        }

        public void setCacheTtl(Duration cacheTtl) {
            this.cacheTtl = cacheTtl;
        }

        public Path getOfflinePackPath() {
            return offlinePackPath;
        }

        public void setOfflinePackPath(Path offlinePackPath) {
            this.offlinePackPath = offlinePackPath;
        }

        public String getOfflineSwarmId() {
            return offlineSwarmId;
        }

        public void setOfflineSwarmId(String offlineSwarmId) {
            this.offlineSwarmId = offlineSwarmId;
        }
    }
}
