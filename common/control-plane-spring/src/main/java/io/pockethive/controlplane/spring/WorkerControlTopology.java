package io.pockethive.controlplane.spring;

import io.pockethive.controlplane.topology.ControlPlaneRouteCatalog;
import io.pockethive.controlplane.topology.ControlPlaneTopologySettings;
import java.util.Map;

/**
 * Responsibility: retain worker-facing names and routes projected from the canonical descriptor.
 * Must not: assemble queue names, duplicate route selection or mutate topology.
 * Contract: docs/architecture/work-plane-boundaries.md#physical-resource-naming-transfer.
 */
public record WorkerControlTopology(String controlQueuePrefix, String controlQueueName, ControlPlaneRouteCatalog routes) {
    static WorkerControlTopology forWorker(String swarmId, String prefix, String role, String instanceId) {
        var settings = new ControlPlaneTopologySettings(swarmId, prefix, Map.of());
        var descriptor = ControlPlaneTopologyDescriptorFactory.forWorkerRole(role.trim(), settings);
        var queue = descriptor.controlQueue(instanceId.trim()).orElseThrow();
        return new WorkerControlTopology(settings.controlQueuePrefix(), queue.name(), descriptor.routes());
    }
    public String getControlQueuePrefix() { return controlQueuePrefix; }
    public String getControlQueueName() { return controlQueueName; }
    public ControlPlaneRouteCatalog getRoutes() { return routes; }
}
