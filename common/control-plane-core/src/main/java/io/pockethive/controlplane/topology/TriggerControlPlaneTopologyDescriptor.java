package io.pockethive.controlplane.topology;

public final class TriggerControlPlaneTopologyDescriptor extends AbstractWorkerTopologyDescriptor {

    public TriggerControlPlaneTopologyDescriptor(String swarmId,
                                                 String controlQueuePrefix,
                                                 QueueDescriptor trafficQueue) {
        super("trigger", swarmId, controlQueuePrefix, trafficQueue);
    }

    public TriggerControlPlaneTopologyDescriptor(ControlPlaneTopologySettings settings) {
        this(settings.swarmId(), settings.controlQueuePrefix(),
            settings.trafficQueueForRole("trigger").orElse(null));
    }
}
