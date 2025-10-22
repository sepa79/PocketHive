package io.pockethive.controlplane.topology;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Settings that provide contextual data required to construct control-plane topology descriptors.
 *
 * @param swarmId             the swarm identifier resolved from configuration
 * @param controlQueuePrefix  the base control queue prefix (without swarm/role/instance suffixes)
 * @param trafficQueues       declared traffic queue descriptors keyed by worker role
 */
public record ControlPlaneTopologySettings(String swarmId,
                                           String controlQueuePrefix,
                                           Map<String, QueueDescriptor> trafficQueues) {

    public ControlPlaneTopologySettings {
        swarmId = requireText("swarmId", swarmId);
        controlQueuePrefix = requireText("controlQueuePrefix", controlQueuePrefix);
        trafficQueues = normaliseQueues(trafficQueues);
    }

    public Optional<QueueDescriptor> trafficQueueForRole(String role) {
        if (role == null) {
            return Optional.empty();
        }
        String key = role.trim();
        if (key.isEmpty()) {
            return Optional.empty();
        }
        return Optional.ofNullable(trafficQueues.get(key));
    }

    private static Map<String, QueueDescriptor> normaliseQueues(Map<String, QueueDescriptor> queues) {
        if (queues == null || queues.isEmpty()) {
            return Map.of();
        }
        return Map.copyOf(queues);
    }

    private static String requireText(String name, String value) {
        Objects.requireNonNull(name, "name");
        if (value == null) {
            throw new IllegalArgumentException(name + " must not be null");
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return trimmed;
    }
}
