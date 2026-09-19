package io.pockethive.orchestrator.runtime;

import java.time.Instant;
import java.util.List;

/**
 * Responsibility: carry the immutable persisted ownership projection of the runtime ownership manifest.
 * Must not: resolve names, access resources or decide lifecycle/cleanup outcomes.
 * Contract: RESP-RUNTIME-CLEANUP — docs/architecture/runtime-responsibilities.md#resp-runtime-cleanup.
 */
public record RuntimeOwnershipManifest(
    String swarmId,
    String runId,
    String templateId,
    String computeAdapter,
    Instant createdAt,
    List<RuntimeManifestObject> runtimeObjects,
    RuntimeRabbitManifest rabbit) {

    public RuntimeOwnershipManifest {
        runtimeObjects = runtimeObjects == null ? List.of() : List.copyOf(runtimeObjects);
        rabbit = rabbit == null ? new RuntimeRabbitManifest(List.of(), List.of(), List.of()) : rabbit;
    }

}
