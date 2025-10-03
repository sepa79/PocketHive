package io.pockethive.worker.sdk.api;

import java.util.Objects;

/**
 * Describes the worker identity and routing metadata.
 */
public record WorkerInfo(
    String role,
    String swarmId,
    String instanceId,
    String inQueue,
    String outQueue
) {

    public WorkerInfo {
        role = requireText(role, "role");
        swarmId = requireText(swarmId, "swarmId");
        instanceId = requireText(instanceId, "instanceId");
        inQueue = normalize(inQueue);
        outQueue = normalize(outQueue);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
