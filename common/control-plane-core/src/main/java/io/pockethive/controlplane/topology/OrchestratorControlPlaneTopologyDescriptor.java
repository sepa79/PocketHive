package io.pockethive.controlplane.topology;

import io.pockethive.topology.control.ControlResourceNamesPort;

import io.pockethive.control.ConfirmationScope;
import io.pockethive.control.CommandResult;
import io.pockethive.controlplane.ControlPlaneEventTypes;
import io.pockethive.controlplane.ControlPlaneRoles;
import io.pockethive.controlplane.routing.ControlPlaneRouting;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Responsibility: select Control recipients and bindings using owner-resolved physical queue names.
 * Must not: construct broker names, select a naming implementation or declare resources.
 * Contract: RESP-WORK-RESOURCE-NAMES — docs/architecture/runtime-responsibilities.md#resp-work-resource-names.
 */
public final class OrchestratorControlPlaneTopologyDescriptor implements ControlPlaneTopologyDescriptor {

    private static final String ROLE = ControlPlaneRoles.ORCHESTRATOR;

    private final String controlQueuePrefix;
    private final ControlResourceNamesPort names;

    public OrchestratorControlPlaneTopologyDescriptor(String controlQueuePrefix, ControlResourceNamesPort names) {
        this.controlQueuePrefix = requireText("controlQueuePrefix", controlQueuePrefix);
        this.names = java.util.Objects.requireNonNull(names, "names");
    }

    public OrchestratorControlPlaneTopologyDescriptor(ControlPlaneTopologySettings settings, ControlResourceNamesPort names) {
        this(settings.controlQueuePrefix(), names);
    }

    @Override
    public String role() {
        return ROLE;
    }

    @Override
    public Optional<ControlQueueDescriptor> controlQueue(String instanceId) {
        String id = requireInstanceId(instanceId);
        String queueName = names.managerControlQueue(controlQueuePrefix, ROLE, id);
        Set<String> executorEvents = Set.of(
            lifecycleEventPattern(CommandResult.KIND),
            lifecycleEventPattern(ControlPlaneEventTypes.JOURNAL_WORK_JOURNAL),
            lifecycleEventPattern(ControlPlaneEventTypes.ALERT_ALERT));
        return Optional.of(new ControlQueueDescriptor(queueName, Set.of(), executorEvents));
    }

    @Override
    public Collection<QueueDescriptor> additionalQueues(String instanceId) {
        return List.of(controllerStatusQueue(instanceId));
    }

    public QueueDescriptor controllerStatusQueue(String instanceId) {
        String id = requireInstanceId(instanceId);
        String queueName = names.controllerStatusQueue(controlQueuePrefix, id);
        Set<String> bindings = Set.of(
            controllerStatusPattern(ControlPlaneEventTypes.STATUS_FULL),
            controllerStatusPattern(ControlPlaneEventTypes.STATUS_DELTA)
        );
        return new QueueDescriptor(queueName, bindings);
    }

    @Override
    public ControlPlaneRouteCatalog routes() {
        Set<String> lifecycleEvents = Set.of(lifecycleEventPattern(CommandResult.KIND));
        Set<String> statusEvents = Set.of(
            controllerStatusPattern(ControlPlaneEventTypes.STATUS_FULL),
            controllerStatusPattern(ControlPlaneEventTypes.STATUS_DELTA)
        );
        return new ControlPlaneRouteCatalog(
            Set.of(), Set.of(), Set.of(), statusEvents, lifecycleEvents,
            Set.of(
                lifecycleEventPattern(ControlPlaneEventTypes.ALERT_ALERT),
                lifecycleEventPattern(ControlPlaneEventTypes.JOURNAL_WORK_JOURNAL)));
    }

    private static String lifecycleEventPattern(String type) {
        String base = ControlPlaneRouting.event(type, ConfirmationScope.EMPTY);
        return base.replace(".ALL.ALL.ALL", ".#");
    }

    private static String controllerStatusPattern(String type) {
        ConfirmationScope scope = new ConfirmationScope("*", ControlPlaneRoles.SWARM_CONTROLLER, "*");
        return ControlPlaneRouting.event("metric", type, scope);
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
