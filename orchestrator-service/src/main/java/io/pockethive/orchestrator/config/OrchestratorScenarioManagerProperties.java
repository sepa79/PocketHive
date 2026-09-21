package io.pockethive.orchestrator.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.Objects;
import org.springframework.validation.annotation.Validated;
import org.springframework.util.Assert;

/**
 * Responsibility: Bind the Scenario Manager client connection settings.
 * Must not: Resolve control-plane topology or provision runtime resources.
 * Contract: docs/orchestrator/configuration.md.
 */
@Validated
public final class OrchestratorScenarioManagerProperties {

    private final String url;
    private final @Valid OrchestratorHttpProperties http;

    public OrchestratorScenarioManagerProperties(@NotBlank String url, @Valid OrchestratorHttpProperties http) {
        Assert.hasText(url, "url must not be blank");
        this.url = url;
        this.http = Objects.requireNonNull(http, "http");
    }

    public String getUrl() {
        return url;
    }

    public OrchestratorHttpProperties getHttp() {
        return http;
    }
}
