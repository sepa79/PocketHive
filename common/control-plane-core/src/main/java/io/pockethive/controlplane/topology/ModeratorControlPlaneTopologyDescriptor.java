package io.pockethive.controlplane.topology;

public final class ModeratorControlPlaneTopologyDescriptor extends AbstractWorkerTopologyDescriptor {

    public ModeratorControlPlaneTopologyDescriptor(String swarmId,
                                                   String controlQueuePrefix,
                                                   QueueDescriptor trafficQueue) {
        super("moderator", swarmId, controlQueuePrefix, trafficQueue);
    }

    public ModeratorControlPlaneTopologyDescriptor(ControlPlaneTopologySettings settings) {
        this(settings.swarmId(), settings.controlQueuePrefix(),
            settings.trafficQueueForRole("moderator").orElse(null));
    }
}
