package io.pockethive.worker.sdk.api;

import java.util.Objects;

/**
 * Describes the worker identity and routing metadata available via {@link WorkerContext#info()}.
 * See {@code docs/sdk/worker-sdk-quickstart.md} for examples of how the runtime populates this data.
 */
public record WorkerInfo(
    String role,
    String swarmId,
    String instanceId,
    String inQueue,
    String outQueue
) {

    /**
     * Creates a new worker description.
     *
     * @param role       logical role name (e.g. {@code processor})
     * @param swarmId    swarm identifier assigned by the control plane
     * @param instanceId unique instance identifier
     * @param inQueue    optional inbound queue name
     * @param outQueue   optional outbound queue name
     */
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
