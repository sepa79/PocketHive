package io.pockethive.controlplane.topology;

import io.pockethive.control.ConfirmationScope;
import io.pockethive.controlplane.routing.ControlPlaneRouting;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public final class OrchestratorControlPlaneTopologyDescriptor implements ControlPlaneTopologyDescriptor {

    private static final String ROLE = "orchestrator";

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
        Set<String> readyErrorEvents = Set.of(
            lifecycleEventPattern("ready"),
            lifecycleEventPattern("error")
        );
        return Optional.of(new ControlQueueDescriptor(queueName, Set.of(), readyErrorEvents));
    }

    @Override
    public Collection<QueueDescriptor> additionalQueues(String instanceId) {
        String id = requireInstanceId(instanceId);
        String queueName = controlQueuePrefix + ".orchestrator-status." + id;
        Set<String> bindings = Set.of(
            controllerStatusPattern("status-full"),
            controllerStatusPattern("status-delta")
        );
        return List.of(new QueueDescriptor(queueName, bindings));
    }

    @Override
    public ControlPlaneRouteCatalog routes() {
        Set<String> lifecycleEvents = Set.of(
            lifecycleEventPattern("ready"),
            lifecycleEventPattern("error")
        );
        Set<String> statusEvents = Set.of(
            controllerStatusPattern("status-full"),
            controllerStatusPattern("status-delta")
        );
        return new ControlPlaneRouteCatalog(Set.of(), Set.of(), Set.of(), statusEvents, lifecycleEvents, Set.of());
    }

    private static String lifecycleEventPattern(String type) {
        String base = ControlPlaneRouting.event(type, ConfirmationScope.EMPTY);
        return base.replace(".ALL.ALL.ALL", ".#");
    }

    private static String controllerStatusPattern(String type) {
        ConfirmationScope scope = new ConfirmationScope(null, "swarm-controller", "*");
        String base = ControlPlaneRouting.event(type, scope);
        return base.replace(".ALL.swarm-controller", ".swarm-controller");
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
