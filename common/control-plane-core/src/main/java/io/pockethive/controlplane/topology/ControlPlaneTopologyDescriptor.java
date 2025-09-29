package io.pockethive.controlplane.topology;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Describes the queues and bindings required for a control-plane participant.
 */
public interface ControlPlaneTopologyDescriptor {

    String role();

    Optional<ControlQueueDescriptor> controlQueue(String instanceId);

    default Collection<QueueDescriptor> additionalQueues(String instanceId) {
        Objects.requireNonNull(instanceId, "instanceId");
        return List.of();
    }

    ControlPlaneRouteCatalog routes();
}
