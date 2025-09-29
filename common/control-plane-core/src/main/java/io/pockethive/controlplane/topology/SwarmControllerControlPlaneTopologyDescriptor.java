package io.pockethive.controlplane.topology;

import io.pockethive.Topology;
import io.pockethive.control.ConfirmationScope;
import io.pockethive.controlplane.routing.ControlPlaneRouting;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

public final class SwarmControllerControlPlaneTopologyDescriptor implements ControlPlaneTopologyDescriptor {

    private static final String ROLE = "swarm-controller";

    @Override
    public String role() {
        return ROLE;
    }

    @Override
    public Optional<ControlQueueDescriptor> controlQueue(String instanceId) {
        String id = requireInstanceId(instanceId);
        String queueName = Topology.CONTROL_QUEUE + "." + ROLE + "." + id;
        LinkedHashSet<String> signals = new LinkedHashSet<>();
        signals.add(ControlPlaneRouting.signal("swarm-start", Topology.SWARM_ID, ROLE, "ALL"));
        signals.add(ControlPlaneRouting.signal("swarm-template", Topology.SWARM_ID, ROLE, "ALL"));
        signals.add(ControlPlaneRouting.signal("swarm-stop", Topology.SWARM_ID, ROLE, "ALL"));
        signals.add(ControlPlaneRouting.signal("swarm-remove", Topology.SWARM_ID, ROLE, "ALL"));
        signals.add(ControlPlaneRouting.signal("config-update", Topology.SWARM_ID, ROLE, "ALL"));
        signals.add(ControlPlaneRouting.signal("config-update", Topology.SWARM_ID, ROLE, id));
        signals.add(ControlPlaneRouting.signal("config-update", Topology.SWARM_ID, "ALL", "ALL"));
        signals.add(ControlPlaneRouting.signal("config-update", "ALL", ROLE, "ALL"));
        signals.add(ControlPlaneRouting.signal("status-request", Topology.SWARM_ID, ROLE, "ALL"));
        signals.add(ControlPlaneRouting.signal("status-request", Topology.SWARM_ID, "ALL", "ALL"));
        signals.add(ControlPlaneRouting.signal("status-request", Topology.SWARM_ID, ROLE, id));
        signals.add(ControlPlaneRouting.signal("status-request", "ALL", ROLE, "ALL"));
        Set<String> events = Set.of(
            statusEventPattern("status-full"),
            statusEventPattern("status-delta")
        );
        return Optional.of(new ControlQueueDescriptor(queueName, signals, events));
    }

    @Override
    public ControlPlaneRouteCatalog routes() {
        Set<String> configRoutes = Set.of(
            ControlPlaneRouting.signal("config-update", "ALL", ROLE, "ALL"),
            ControlPlaneRouting.signal("config-update", Topology.SWARM_ID, ROLE, "ALL"),
            ControlPlaneRouting.signal("config-update", Topology.SWARM_ID, ROLE, ControlPlaneRouteCatalog.INSTANCE_TOKEN),
            ControlPlaneRouting.signal("config-update", Topology.SWARM_ID, "ALL", "ALL")
        );
        Set<String> statusRoutes = Set.of(
            ControlPlaneRouting.signal("status-request", "ALL", ROLE, "ALL"),
            ControlPlaneRouting.signal("status-request", Topology.SWARM_ID, ROLE, "ALL"),
            ControlPlaneRouting.signal("status-request", Topology.SWARM_ID, ROLE, ControlPlaneRouteCatalog.INSTANCE_TOKEN),
            ControlPlaneRouting.signal("status-request", Topology.SWARM_ID, "ALL", "ALL")
        );
        Set<String> lifecycleRoutes = Set.of(
            ControlPlaneRouting.signal("swarm-start", Topology.SWARM_ID, ROLE, "ALL"),
            ControlPlaneRouting.signal("swarm-template", Topology.SWARM_ID, ROLE, "ALL"),
            ControlPlaneRouting.signal("swarm-stop", Topology.SWARM_ID, ROLE, "ALL"),
            ControlPlaneRouting.signal("swarm-remove", Topology.SWARM_ID, ROLE, "ALL")
        );
        Set<String> statusEvents = Set.of(
            statusEventPattern("status-full"),
            statusEventPattern("status-delta")
        );
        return new ControlPlaneRouteCatalog(configRoutes, statusRoutes, lifecycleRoutes, statusEvents, Set.of(), Set.of());
    }

    private static String statusEventPattern(String type) {
        String base = ControlPlaneRouting.event(type, ConfirmationScope.forSwarm(Topology.SWARM_ID));
        return base.replace(".ALL.ALL", ".#");
    }

    private static String requireInstanceId(String instanceId) {
        if (instanceId == null || instanceId.isBlank()) {
            throw new IllegalArgumentException("instanceId must not be blank");
        }
        return instanceId;
    }
}
