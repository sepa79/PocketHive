package io.pockethive.orchestrator.config;

import jakarta.validation.Valid;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Responsibility: Bind the explicit Orchestrator application settings tree.
 * Must not: Own control-plane queue names, routing, or infrastructure effects.
 * Contract: docs/orchestrator/configuration.md; unknown application settings fail startup.
 */
@Validated
@ConfigurationProperties(prefix = "pockethive.control-plane.orchestrator", ignoreUnknownFields = false)
public class OrchestratorProperties {

    private final @Valid OrchestratorMetricsProperties metrics;
    private final @Valid OrchestratorDockerProperties docker;
    private final @Valid OrchestratorImageProperties images;
    private final @Valid OrchestratorScenarioManagerProperties scenarioManager;
    private final @Valid OrchestratorNetworkProxyManagerProperties networkProxyManager;

    public OrchestratorProperties(@Valid OrchestratorMetricsProperties metrics,
                                  @Valid OrchestratorDockerProperties docker,
                                  @Valid OrchestratorImageProperties images,
                                  @Valid OrchestratorScenarioManagerProperties scenarioManager,
                                  @Valid OrchestratorNetworkProxyManagerProperties networkProxyManager) {
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.docker = Objects.requireNonNull(docker, "docker");
        this.images = Objects.requireNonNull(images, "images");
        this.scenarioManager = Objects.requireNonNull(scenarioManager, "scenarioManager");
        this.networkProxyManager = Objects.requireNonNull(networkProxyManager, "networkProxyManager");
    }

    public OrchestratorMetricsProperties getMetrics() {
        return metrics;
    }

    public OrchestratorDockerProperties getDocker() {
        return docker;
    }

    public String getImageRepositoryPrefix() {
        return images.getRepositoryPrefix();
    }

    public OrchestratorScenarioManagerProperties getScenarioManager() {
        return scenarioManager;
    }

    public OrchestratorNetworkProxyManagerProperties getNetworkProxyManager() {
        return networkProxyManager;
    }
}
