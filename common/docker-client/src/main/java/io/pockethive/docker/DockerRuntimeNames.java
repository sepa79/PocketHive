package io.pockethive.docker;

import java.util.Locale;

/**
 * Responsibility: resolve the canonical Docker stack name for a swarm.
 * Must not: read environment settings or create resources.
 * Contract: RESP-DOCKER-RUNTIME — docs/architecture/runtime-responsibilities.md#resp-docker-runtime.
 */
public final class DockerRuntimeNames {
    public static final String STACK_NAME_ENV = "POCKETHIVE_RUNTIME_STACK_NAME";

    private DockerRuntimeNames() {
    }

    public static String stackName(String swarmId) {
        if (swarmId == null || swarmId.isBlank()) {
            throw new IllegalArgumentException("swarmId must not be blank");
        }
        return "ph-" + swarmId.toLowerCase(Locale.ROOT);
    }
}
