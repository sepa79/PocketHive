package io.pockethive.controlplane.topology;

import io.pockethive.Topology;
import java.util.Set;

public final class ModeratorControlPlaneTopologyDescriptor extends AbstractWorkerTopologyDescriptor {

    public ModeratorControlPlaneTopologyDescriptor() {
        super("moderator", QueueDescriptor.traffic(Topology.GEN_QUEUE, Set.of(Topology.GEN_QUEUE)));
    }
}
