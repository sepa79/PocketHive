package io.pockethive.work.config;

import java.util.Map;

/**
 * Responsibility: retain an adapter's resolved bootstrap configuration and matching environment projection.
 * Must not: resolve settings, choose an adapter or perform resource operations.
 * Contract: RESP-WORK-CONNECTION-ENVIRONMENT — docs/architecture/runtime-responsibilities.md#resp-work-connection-environment.
 */
public record WorkBootstrapProjection(Map<String, Object> configuration, Map<String, String> environment) {
    public WorkBootstrapProjection {
        configuration = Map.copyOf(configuration);
        environment = Map.copyOf(environment);
    }
    @Override public String toString() { return "WorkBootstrapProjection[redacted]"; }
}
