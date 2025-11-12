package io.pockethive.controlplane.topology;

/**
 * Generic worker topology descriptor that simply reuses the role name supplied at runtime.
 * This allows PocketHive to load arbitrary worker/plugin roles without having to pre-register
 * descriptor classes for each role.
 */
public final class GenericWorkerControlPlaneTopologyDescriptor extends AbstractWorkerTopologyDescriptor {

    public GenericWorkerControlPlaneTopologyDescriptor(String role,
                                                       ControlPlaneTopologySettings settings) {
        this(role, settings.swarmId(), settings.controlQueuePrefix(),
            settings.trafficQueueForRole(role).orElse(null));
    }

    public GenericWorkerControlPlaneTopologyDescriptor(String role,
                                                       String swarmId,
                                                       String controlQueuePrefix,
                                                       QueueDescriptor trafficQueue) {
        super(role, swarmId, controlQueuePrefix, trafficQueue);
    }
}
