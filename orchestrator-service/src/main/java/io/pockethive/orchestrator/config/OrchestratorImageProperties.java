package io.pockethive.orchestrator.config;

import org.springframework.validation.annotation.Validated;

/**
 * Responsibility: Normalize the configured Orchestrator image repository prefix.
 * Must not: Resolve control-plane topology or provision runtime resources.
 * Contract: docs/orchestrator/configuration.md.
 */
@Validated
public final class OrchestratorImageProperties {

    private final String repositoryPrefix;

    public OrchestratorImageProperties(String repositoryPrefix) {
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
