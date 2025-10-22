package io.pockethive.controlplane.topology;

public final class ProcessorControlPlaneTopologyDescriptor extends AbstractWorkerTopologyDescriptor {

    public ProcessorControlPlaneTopologyDescriptor(String swarmId,
                                                   String controlQueuePrefix,
                                                   QueueDescriptor trafficQueue) {
        super("processor", swarmId, controlQueuePrefix, trafficQueue);
    }

    public ProcessorControlPlaneTopologyDescriptor(ControlPlaneTopologySettings settings) {
        this(settings.swarmId(), settings.controlQueuePrefix(),
            settings.trafficQueueForRole("processor").orElse(null));
    }
}
