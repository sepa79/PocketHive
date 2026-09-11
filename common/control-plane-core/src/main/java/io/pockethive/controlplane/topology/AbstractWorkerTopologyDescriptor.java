package io.pockethive.controlplane.topology;

import io.pockethive.topology.control.ControlResourceNamesPort;

import io.pockethive.controlplane.ControlPlaneSignals;
import io.pockethive.controlplane.routing.ControlPlaneRouting;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Responsibility: select Control recipients and bindings using owner-resolved physical queue names.
 * Must not: construct broker names, select a naming implementation or declare resources.
 * Contract: docs/architecture/work-plane-boundaries.md#physical-resource-naming-transfer.
 */
abstract class AbstractWorkerTopologyDescriptor implements ControlPlaneTopologyDescriptor {

    private final String role;
    private final String swarmId;
    private final String controlQueuePrefix;
    private final ControlResourceNamesPort names;
    private final Optional<QueueDescriptor> trafficQueue;

    protected AbstractWorkerTopologyDescriptor(String role,
                                               String swarmId,
                                               String controlQueuePrefix,
                                               QueueDescriptor trafficQueue, ControlResourceNamesPort names) {
        this.role = requireRole(role);
        this.swarmId = requireText("swarmId", swarmId);
        this.controlQueuePrefix = requireText("controlQueuePrefix", controlQueuePrefix);
        this.names = java.util.Objects.requireNonNull(names, "names");
        this.trafficQueue = Optional.ofNullable(trafficQueue);
    }

    @Override
    public String role() {
        return role;
    }

    @Override
    public Optional<ControlQueueDescriptor> controlQueue(String instanceId) {
        String id = requireInstanceId(instanceId);
        String queueName = names.workerControlQueue(controlQueuePrefix, swarmId, role, id);
        Set<String> configSignals = Set.of(
            ControlPlaneRouting.signal(ControlPlaneSignals.CONFIG_UPDATE, "ALL", role, "ALL"),
            ControlPlaneRouting.signal(ControlPlaneSignals.CONFIG_UPDATE, swarmId, role, "ALL"),
            ControlPlaneRouting.signal(ControlPlaneSignals.CONFIG_UPDATE, swarmId, role, id),
            ControlPlaneRouting.signal(ControlPlaneSignals.CONFIG_UPDATE, swarmId, "ALL", "ALL")
        );
        Set<String> statusSignals = Set.of(
            ControlPlaneRouting.signal(ControlPlaneSignals.STATUS_REQUEST, "ALL", role, "ALL"),
            ControlPlaneRouting.signal(ControlPlaneSignals.STATUS_REQUEST, swarmId, role, "ALL"),
            ControlPlaneRouting.signal(ControlPlaneSignals.STATUS_REQUEST, swarmId, role, id),
            ControlPlaneRouting.signal(ControlPlaneSignals.STATUS_REQUEST, swarmId, "ALL", "ALL")
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
            ControlPlaneRouting.signal(ControlPlaneSignals.CONFIG_UPDATE, swarmId, role, "ALL"),
            ControlPlaneRouting.signal(ControlPlaneSignals.CONFIG_UPDATE, swarmId, role, ControlPlaneRouteCatalog.INSTANCE_TOKEN),
            ControlPlaneRouting.signal(ControlPlaneSignals.CONFIG_UPDATE, swarmId, "ALL", "ALL")
        );
        Set<String> statusRoutes = Set.of(
            ControlPlaneRouting.signal(ControlPlaneSignals.STATUS_REQUEST, "ALL", role, "ALL"),
            ControlPlaneRouting.signal(ControlPlaneSignals.STATUS_REQUEST, swarmId, role, "ALL"),
            ControlPlaneRouting.signal(ControlPlaneSignals.STATUS_REQUEST, swarmId, role, ControlPlaneRouteCatalog.INSTANCE_TOKEN),
            ControlPlaneRouting.signal(ControlPlaneSignals.STATUS_REQUEST, swarmId, "ALL", "ALL")
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

    private static String requireText(String name, String value) {
        if (value == null) {
            throw new IllegalArgumentException(name + " must not be null");
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return trimmed;
    }
}
