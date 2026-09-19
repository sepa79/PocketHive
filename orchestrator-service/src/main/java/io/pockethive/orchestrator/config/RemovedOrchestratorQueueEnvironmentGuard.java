package io.pockethive.orchestrator.config;

import java.util.List;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Responsibility: Reject the removed Orchestrator queue-prefix environment overrides at startup.
 * Must not: Resolve queue names, map legacy values, or supply replacement settings.
 * Contract: docs/orchestrator/configuration.md; Spring strict binding covers property keys but ignores unknown env keys.
 */
@Component
public final class RemovedOrchestratorQueueEnvironmentGuard {

    private static final List<String> REMOVED_ENVIRONMENT_KEYS = List.of(
        "POCKETHIVE_CONTROL_PLANE_ORCHESTRATOR_CONTROL_QUEUE_PREFIX",
        "POCKETHIVE_CONTROL_PLANE_ORCHESTRATOR_STATUS_QUEUE_PREFIX");

    public RemovedOrchestratorQueueEnvironmentGuard(Environment environment) {
        for (String key : REMOVED_ENVIRONMENT_KEYS) {
            if (environment.containsProperty(key)) {
                throw new IllegalArgumentException(key
                    + " is removed; configure pockethive.control-plane.control-queue-prefix instead");
            }
        }
    }
}
