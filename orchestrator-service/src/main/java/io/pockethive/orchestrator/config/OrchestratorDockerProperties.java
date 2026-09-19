package io.pockethive.orchestrator.config;

import io.pockethive.manager.runtime.ComputeAdapterType;
import jakarta.validation.constraints.NotBlank;
import org.springframework.validation.annotation.Validated;
import org.springframework.util.Assert;

/**
 * Responsibility: Bind Orchestrator compute connection settings.
 * Must not: Resolve control-plane topology or provision runtime resources.
 * Contract: docs/orchestrator/configuration.md.
 */
@Validated
public final class OrchestratorDockerProperties {

    private final String socketPath;
    private final ComputeAdapterType computeAdapter;

    public OrchestratorDockerProperties(@NotBlank String socketPath, ComputeAdapterType computeAdapter) {
        Assert.hasText(socketPath, "socketPath must not be blank");
        this.socketPath = socketPath;
        // For orchestrator we want AUTO as the default so it can decide
        // between single-node Docker and Swarm services based on the
        // runtime environment. Explicit values are honoured as-is.
        this.computeAdapter = computeAdapter == null ? ComputeAdapterType.AUTO : computeAdapter;
    }

    public String getSocketPath() {
        return socketPath;
    }

    public ComputeAdapterType getComputeAdapter() {
        return computeAdapter;
    }
}
