package io.pockethive.controlplane.topology;

import io.pockethive.topology.control.ControlResourceNamesPort;

/**
 * Responsibility: select Control recipients and bindings using owner-resolved physical queue names.
 * Must not: construct broker names, select a naming implementation or declare resources.
 * Contract: docs/architecture/work-plane-boundaries.md#physical-resource-naming-transfer.
 */
public final class WorkerControlPlaneTopologyDescriptor extends AbstractWorkerTopologyDescriptor {

    public WorkerControlPlaneTopologyDescriptor(String role,
                                                String swarmId,
                                                String controlQueuePrefix,
                                                QueueDescriptor trafficQueue, ControlResourceNamesPort names) {
        super(role, swarmId, controlQueuePrefix, trafficQueue, names);
    }

    public WorkerControlPlaneTopologyDescriptor(String role, ControlPlaneTopologySettings settings, ControlResourceNamesPort names) {
        this(role, settings.swarmId(), settings.controlQueuePrefix(),
            settings.trafficQueueForRole(role).orElse(null), names);
    }
}
