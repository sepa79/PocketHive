package io.pockethive.controlplane.topology;

/**
 * Generic worker control-plane topology descriptor that derives bindings from the worker role.
 */
public final class WorkerControlPlaneTopologyDescriptor extends AbstractWorkerTopologyDescriptor {

    public WorkerControlPlaneTopologyDescriptor(String role,
                                                String swarmId,
                                                String controlQueuePrefix,
                                                QueueDescriptor trafficQueue) {
        super(role, swarmId, controlQueuePrefix, trafficQueue);
    }

    public WorkerControlPlaneTopologyDescriptor(String role, ControlPlaneTopologySettings settings) {
        this(role, settings.swarmId(), settings.controlQueuePrefix(),
            settings.trafficQueueForRole(role).orElse(null));
    }
}
