package io.pockethive.controlplane.topology;

import io.pockethive.control.ConfirmationScope;
import io.pockethive.control.CommandResult;
import io.pockethive.controlplane.ControlPlaneEventTypes;
import io.pockethive.controlplane.ControlPlaneRoles;
import io.pockethive.controlplane.routing.ControlPlaneRouting;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public final class OrchestratorControlPlaneTopologyDescriptor implements ControlPlaneTopologyDescriptor {

    private static final String ROLE = ControlPlaneRoles.ORCHESTRATOR;

    private final String controlQueuePrefix;

    public OrchestratorControlPlaneTopologyDescriptor(String controlQueuePrefix) {
        this.controlQueuePrefix = requireText("controlQueuePrefix", controlQueuePrefix);
    }

    public OrchestratorControlPlaneTopologyDescriptor(ControlPlaneTopologySettings settings) {
        this(settings.controlQueuePrefix());
    }

    @Override
    public String role() {
        return ROLE;
    }

    @Override
    public Optional<ControlQueueDescriptor> controlQueue(String instanceId) {
        String id = requireInstanceId(instanceId);
        String queueName = controlQueuePrefix + "." + ROLE + "." + id;
        Set<String> executorEvents = Set.of(
            lifecycleEventPattern(CommandResult.KIND),
            lifecycleEventPattern(ControlPlaneEventTypes.JOURNAL_WORK_JOURNAL),
            lifecycleEventPattern(ControlPlaneEventTypes.ALERT_ALERT));
        return Optional.of(new ControlQueueDescriptor(queueName, Set.of(), executorEvents));
    }

    @Override
    public Collection<QueueDescriptor> additionalQueues(String instanceId) {
        String id = requireInstanceId(instanceId);
        String queueName = controlQueuePrefix + ".orchestrator-status." + id;
        Set<String> bindings = Set.of(
            controllerStatusPattern(ControlPlaneEventTypes.STATUS_FULL),
            controllerStatusPattern(ControlPlaneEventTypes.STATUS_DELTA)
        );
        return List.of(new QueueDescriptor(queueName, bindings));
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
