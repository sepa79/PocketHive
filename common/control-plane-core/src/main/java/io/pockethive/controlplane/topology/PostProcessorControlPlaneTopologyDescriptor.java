package io.pockethive.controlplane.topology;

import io.pockethive.Topology;
import java.util.Set;

import io.pockethive.controlplane.topology.QueueDescriptor.ExchangeScope;

public final class PostProcessorControlPlaneTopologyDescriptor extends AbstractWorkerTopologyDescriptor {

    public PostProcessorControlPlaneTopologyDescriptor() {
        super("postprocessor", new QueueDescriptor(Topology.FINAL_QUEUE, Set.of(Topology.FINAL_QUEUE), ExchangeScope.TRAFFIC));
    }
}
