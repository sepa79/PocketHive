package io.pockethive.controlplane.topology;

public final class GeneratorControlPlaneTopologyDescriptor extends AbstractWorkerTopologyDescriptor {

    public GeneratorControlPlaneTopologyDescriptor(String swarmId,
                                                   String controlQueuePrefix,
                                                   QueueDescriptor trafficQueue) {
        super("generator", swarmId, controlQueuePrefix, trafficQueue);
    }

    public GeneratorControlPlaneTopologyDescriptor(ControlPlaneTopologySettings settings) {
        this(settings.swarmId(), settings.controlQueuePrefix(),
            settings.trafficQueueForRole("generator").orElse(null));
    }
}
