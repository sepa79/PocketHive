package io.pockethive.docker;

import java.util.Map;

/**
 * Responsibility: expose a read-only Docker inventory snapshot without Docker SDK types.
 * Must not: infer ownership, cleanup eligibility or lifecycle completion.
 * Contract: RESP-DOCKER-RUNTIME — docs/architecture/runtime-responsibilities.md#resp-docker-runtime.
 */
public record DockerRuntimeResource(
    String runtimeId, DockerRuntimeKind kind, String name, String image, String state,
    String createdAt, String startedAt, String finishedAt, Map<String, String> labels) {
    public DockerRuntimeResource {
        labels = labels == null ? Map.of() : Map.copyOf(labels);
    }
}
