package io.pockethive.controlplane.topology;

import io.pockethive.Topology;
import java.util.Set;

import io.pockethive.controlplane.topology.QueueDescriptor.ExchangeScope;

public final class ModeratorControlPlaneTopologyDescriptor extends AbstractWorkerTopologyDescriptor {

    public ModeratorControlPlaneTopologyDescriptor() {
        super("moderator", new QueueDescriptor(Topology.GEN_QUEUE, Set.of(Topology.GEN_QUEUE), ExchangeScope.TRAFFIC));
    }
}
