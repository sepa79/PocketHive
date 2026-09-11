package io.pockethive.controlplane.topology;

import io.pockethive.topology.control.ControlResourceNamesPort;

import io.pockethive.control.ConfirmationScope;
import io.pockethive.controlplane.ControlPlaneSignals;
import io.pockethive.controlplane.ControlPlaneRoles;
import io.pockethive.controlplane.ControlPlaneEventTypes;
import io.pockethive.controlplane.routing.ControlPlaneRouting;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

/**
 * Responsibility: select Control recipients and bindings using owner-resolved physical queue names.
 * Must not: construct broker names, select a naming implementation or declare resources.
 * Contract: docs/architecture/work-plane-boundaries.md#physical-resource-naming-transfer.
 */
public final class SwarmControllerControlPlaneTopologyDescriptor implements ControlPlaneTopologyDescriptor {

    private static final String ROLE = ControlPlaneRoles.SWARM_CONTROLLER;

    private final String swarmId;
    private final String controlQueuePrefix;
    private final ControlResourceNamesPort names;

    public SwarmControllerControlPlaneTopologyDescriptor(String swarmId, String controlQueuePrefix, ControlResourceNamesPort names) {
        this.swarmId = requireText("swarmId", swarmId);
        this.controlQueuePrefix = requireText("controlQueuePrefix", controlQueuePrefix);
        this.names = java.util.Objects.requireNonNull(names, "names");
    }

    public SwarmControllerControlPlaneTopologyDescriptor(ControlPlaneTopologySettings settings, ControlResourceNamesPort names) {
        this(settings.swarmId(), settings.controlQueuePrefix(), names);
    }

    @Override
    public String role() {
        return ROLE;
    }

    @Override
    public Optional<ControlQueueDescriptor> controlQueue(String instanceId) {
        String id = requireInstanceId(instanceId);
        String queueName = names.swarmControllerQueue(controlQueuePrefix, swarmId, ROLE, id);
        LinkedHashSet<String> signals = new LinkedHashSet<>();
        // Lifecycle commands must target a concrete controller instance.
        signals.add(ControlPlaneRouting.signal(ControlPlaneSignals.SWARM_START, swarmId, ROLE, id));
        signals.add(ControlPlaneRouting.signal(ControlPlaneSignals.SWARM_STOP, swarmId, ROLE, id));
        signals.add(ControlPlaneRouting.signal(ControlPlaneSignals.SWARM_REMOVE, swarmId, ROLE, id));
        signals.add(ControlPlaneRouting.signal(ControlPlaneSignals.CONFIG_UPDATE, swarmId, ROLE, "ALL"));
        signals.add(ControlPlaneRouting.signal(ControlPlaneSignals.CONFIG_UPDATE, swarmId, ROLE, id));
        signals.add(ControlPlaneRouting.signal(ControlPlaneSignals.CONFIG_UPDATE, swarmId, "ALL", "ALL"));
        signals.add(ControlPlaneRouting.signal(ControlPlaneSignals.CONFIG_UPDATE, "ALL", ROLE, "ALL"));
        signals.add(ControlPlaneRouting.signal(ControlPlaneSignals.STATUS_REQUEST, swarmId, ROLE, "ALL"));
        signals.add(ControlPlaneRouting.signal(ControlPlaneSignals.STATUS_REQUEST, swarmId, "ALL", "ALL"));
        signals.add(ControlPlaneRouting.signal(ControlPlaneSignals.STATUS_REQUEST, swarmId, ROLE, id));
        signals.add(ControlPlaneRouting.signal(ControlPlaneSignals.STATUS_REQUEST, "ALL", ROLE, "ALL"));
        Set<String> events = Set.of(
            statusEventPattern(ControlPlaneEventTypes.STATUS_FULL),
            statusEventPattern(ControlPlaneEventTypes.STATUS_DELTA),
            alertEventPattern()
        );
        return Optional.of(new ControlQueueDescriptor(queueName, signals, events));
    }

    @Override
    public ControlPlaneRouteCatalog routes() {
        Set<String> configRoutes = Set.of(
            ControlPlaneRouting.signal(ControlPlaneSignals.CONFIG_UPDATE, "ALL", ROLE, "ALL"),
            ControlPlaneRouting.signal(ControlPlaneSignals.CONFIG_UPDATE, swarmId, ROLE, "ALL"),
            ControlPlaneRouting.signal(ControlPlaneSignals.CONFIG_UPDATE, swarmId, ROLE, ControlPlaneRouteCatalog.INSTANCE_TOKEN),
            ControlPlaneRouting.signal(ControlPlaneSignals.CONFIG_UPDATE, swarmId, "ALL", "ALL")
        );
        Set<String> statusRoutes = Set.of(
            ControlPlaneRouting.signal(ControlPlaneSignals.STATUS_REQUEST, "ALL", ROLE, "ALL"),
            ControlPlaneRouting.signal(ControlPlaneSignals.STATUS_REQUEST, swarmId, ROLE, "ALL"),
            ControlPlaneRouting.signal(ControlPlaneSignals.STATUS_REQUEST, swarmId, ROLE, ControlPlaneRouteCatalog.INSTANCE_TOKEN),
            ControlPlaneRouting.signal(ControlPlaneSignals.STATUS_REQUEST, swarmId, "ALL", "ALL")
        );
        Set<String> lifecycleRoutes = Set.of(
            ControlPlaneRouting.signal(ControlPlaneSignals.SWARM_START, swarmId, ROLE, ControlPlaneRouteCatalog.INSTANCE_TOKEN),
            ControlPlaneRouting.signal(ControlPlaneSignals.SWARM_STOP, swarmId, ROLE, ControlPlaneRouteCatalog.INSTANCE_TOKEN),
            ControlPlaneRouting.signal(ControlPlaneSignals.SWARM_REMOVE, swarmId, ROLE, ControlPlaneRouteCatalog.INSTANCE_TOKEN)
        );
        Set<String> statusEvents = Set.of(
            statusEventPattern(ControlPlaneEventTypes.STATUS_FULL),
            statusEventPattern(ControlPlaneEventTypes.STATUS_DELTA)
        );
        Set<String> otherEvents = Set.of(
            alertEventPattern()
        );
        return new ControlPlaneRouteCatalog(configRoutes, statusRoutes, lifecycleRoutes, statusEvents, Set.of(), otherEvents);
    }

    private String statusEventPattern(String type) {
        String base = ControlPlaneRouting.event("metric", type, ConfirmationScope.forSwarm(swarmId));
        return base.replace(".ALL.ALL", ".#");
    }

    private String alertEventPattern() {
        String base = ControlPlaneRouting.event("alert", "*", ConfirmationScope.forSwarm(swarmId));
        return base.replace(".ALL.ALL", ".#");
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
