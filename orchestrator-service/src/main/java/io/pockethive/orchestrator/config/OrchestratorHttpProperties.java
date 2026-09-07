package io.pockethive.orchestrator.config;

import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.util.Objects;
import org.springframework.validation.annotation.Validated;

/**
 * Responsibility: Validate HTTP client timeout settings.
 * Must not: Resolve control-plane topology or provision runtime resources.
 * Contract: docs/orchestrator/configuration.md.
 */
@Validated
public final class OrchestratorHttpProperties {

    private final Duration connectTimeout;
    private final Duration readTimeout;

    public OrchestratorHttpProperties(@NotNull Duration connectTimeout, @NotNull Duration readTimeout) {
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
