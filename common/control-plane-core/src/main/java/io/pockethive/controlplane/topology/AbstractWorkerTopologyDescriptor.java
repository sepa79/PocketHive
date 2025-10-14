package io.pockethive.controlplane.topology;

import io.pockethive.Topology;
import io.pockethive.controlplane.ControlPlaneSignals;
import io.pockethive.controlplane.routing.ControlPlaneRouting;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

abstract class AbstractWorkerTopologyDescriptor implements ControlPlaneTopologyDescriptor {

    private final String role;
    private final Optional<QueueDescriptor> trafficQueue;

    protected AbstractWorkerTopologyDescriptor(String role) {
        this(role, null);
    }

    protected AbstractWorkerTopologyDescriptor(String role, QueueDescriptor trafficQueue) {
        this.role = requireRole(role);
        this.trafficQueue = Optional.ofNullable(trafficQueue);
    }

    @Override
    public String role() {
        return role;
    }

    @Override
    public Optional<ControlQueueDescriptor> controlQueue(String instanceId) {
        String id = requireInstanceId(instanceId);
        String queueName = Topology.CONTROL_QUEUE + "." + Topology.SWARM_ID + "." + role + "." + id;
        Set<String> configSignals = Set.of(
            ControlPlaneRouting.signal(ControlPlaneSignals.CONFIG_UPDATE, "ALL", role, "ALL"),
            ControlPlaneRouting.signal(ControlPlaneSignals.CONFIG_UPDATE, Topology.SWARM_ID, role, "ALL"),
            ControlPlaneRouting.signal(ControlPlaneSignals.CONFIG_UPDATE, Topology.SWARM_ID, role, id),
            ControlPlaneRouting.signal(ControlPlaneSignals.CONFIG_UPDATE, Topology.SWARM_ID, "ALL", "ALL")
        );
        Set<String> statusSignals = Set.of(
            ControlPlaneRouting.signal(ControlPlaneSignals.STATUS_REQUEST, "ALL", role, "ALL"),
            ControlPlaneRouting.signal(ControlPlaneSignals.STATUS_REQUEST, Topology.SWARM_ID, role, "ALL"),
            ControlPlaneRouting.signal(ControlPlaneSignals.STATUS_REQUEST, Topology.SWARM_ID, role, id)
        );
        LinkedHashSet<String> allSignals = new LinkedHashSet<>(configSignals);
        allSignals.addAll(statusSignals);
        return Optional.of(new ControlQueueDescriptor(queueName, allSignals, Set.of()));
    }

    @Override
    public Collection<QueueDescriptor> additionalQueues(String instanceId) {
        requireInstanceId(instanceId);
        return trafficQueue.<Collection<QueueDescriptor>>map(List::of).orElseGet(List::of);
    }

    @Override
    public ControlPlaneRouteCatalog routes() {
        Set<String> configRoutes = Set.of(
            ControlPlaneRouting.signal(ControlPlaneSignals.CONFIG_UPDATE, "ALL", role, "ALL"),
            ControlPlaneRouting.signal(ControlPlaneSignals.CONFIG_UPDATE, Topology.SWARM_ID, role, "ALL"),
            ControlPlaneRouting.signal(ControlPlaneSignals.CONFIG_UPDATE, Topology.SWARM_ID, role, ControlPlaneRouteCatalog.INSTANCE_TOKEN),
            ControlPlaneRouting.signal(ControlPlaneSignals.CONFIG_UPDATE, Topology.SWARM_ID, "ALL", "ALL")
        );
        Set<String> statusRoutes = Set.of(
            ControlPlaneRouting.signal(ControlPlaneSignals.STATUS_REQUEST, "ALL", role, "ALL"),
            ControlPlaneRouting.signal(ControlPlaneSignals.STATUS_REQUEST, Topology.SWARM_ID, role, "ALL"),
            ControlPlaneRouting.signal(ControlPlaneSignals.STATUS_REQUEST, Topology.SWARM_ID, role, ControlPlaneRouteCatalog.INSTANCE_TOKEN)
        );
        return new ControlPlaneRouteCatalog(configRoutes, statusRoutes, Set.of(), Set.of(), Set.of(), Set.of());
    }

    private static String requireRole(String role) {
        if (role == null || role.isBlank()) {
            throw new IllegalArgumentException("role must not be blank");
        }
        return role;
    }

    private static String requireInstanceId(String instanceId) {
        if (instanceId == null || instanceId.isBlank()) {
            throw new IllegalArgumentException("instanceId must not be blank");
        }
        return instanceId;
    }
}
