package io.pockethive.controlplane.topology;

public final class PostProcessorControlPlaneTopologyDescriptor extends AbstractWorkerTopologyDescriptor {

    public PostProcessorControlPlaneTopologyDescriptor(String swarmId,
                                                       String controlQueuePrefix,
                                                       QueueDescriptor trafficQueue) {
        super("postprocessor", swarmId, controlQueuePrefix, trafficQueue);
    }

    public PostProcessorControlPlaneTopologyDescriptor(ControlPlaneTopologySettings settings) {
        this(settings.swarmId(), settings.controlQueuePrefix(),
            settings.trafficQueueForRole("postprocessor").orElse(null));
    }
}
