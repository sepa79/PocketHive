package io.pockethive.work.config.environment;

import java.util.Map;

/**
 * Responsibility: carry the verified immutable environment snapshot and its accepted bootstrap projection.
 * Must not: validate other Work fields, resolve settings or expose credentials in diagnostics.
 * Contract: RESP-WORK-CONNECTION-ENVIRONMENT — docs/architecture/runtime-responsibilities.md#resp-work-connection-environment.
 */
public record ResolvedWorkConnectionEnvironment(Map<String, String> environment, Map<String, Object> bootstrapConfig) {
    public ResolvedWorkConnectionEnvironment {
        environment = Map.copyOf(environment);
        bootstrapConfig = Map.copyOf(bootstrapConfig);
    }

    @Override
    public String toString() {
        return "ResolvedWorkConnectionEnvironment[redacted]";
    }
}
