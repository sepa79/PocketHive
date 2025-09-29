package io.pockethive.controlplane.topology;

import io.pockethive.Topology;
import java.util.Set;

public final class ProcessorControlPlaneTopologyDescriptor extends AbstractWorkerTopologyDescriptor {

    public ProcessorControlPlaneTopologyDescriptor() {
        super("processor", new QueueDescriptor(Topology.MOD_QUEUE, Set.of(Topology.MOD_QUEUE)));
    }
}
