package io.pockethive.orchestrator.runtime;

import io.pockethive.swarm.model.lifecycle.ResourcePlane;
import java.util.Optional;
import io.pockethive.orchestrator.runtime.RuntimeCleanupPorts.RabbitQueueResource;
import io.pockethive.orchestrator.runtime.RuntimeCleanupPorts.RabbitExchangeResource;

/**
 * Responsibility: observe and remove explicitly scoped Rabbit resources.
 * Must not: infer plane from resource names or bypass the owning resource operation.
 * Contract: docs/architecture/work-plane-boundaries.md#connection-split-prerequisite-resource-identity.
 */
public interface RabbitTopologyPort {
    String connectionIdentity(ResourcePlane plane);

    Optional<RabbitQueueResource> queue(ResourcePlane plane, String name);

    Optional<RabbitExchangeResource> exchange(ResourcePlane plane, String name);

    void deleteQueue(ResourcePlane plane, String name);

    void deleteExchange(ResourcePlane plane, String name);
}
