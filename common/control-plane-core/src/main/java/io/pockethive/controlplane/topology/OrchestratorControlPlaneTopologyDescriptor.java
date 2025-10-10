package io.pockethive.controlplane.topology;

import io.pockethive.Topology;
import io.pockethive.control.ConfirmationScope;
import io.pockethive.controlplane.routing.ControlPlaneRouting;
import io.pockethive.controlplane.topology.QueueDescriptor.ExchangeScope;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public final class OrchestratorControlPlaneTopologyDescriptor implements ControlPlaneTopologyDescriptor {

    private static final String ROLE = "orchestrator";

    @Override
    public String role() {
        return ROLE;
    }

    @Override
    public Optional<ControlQueueDescriptor> controlQueue(String instanceId) {
        String id = requireInstanceId(instanceId);
        String queueName = Topology.CONTROL_QUEUE + "." + ROLE + "." + id;
        Set<String> readyErrorEvents = Set.of(
            lifecycleEventPattern("ready"),
            lifecycleEventPattern("error")
        );
        return Optional.of(new ControlQueueDescriptor(queueName, Set.of(), readyErrorEvents));
    }

    @Override
    public Collection<QueueDescriptor> additionalQueues(String instanceId) {
        String id = requireInstanceId(instanceId);
        String queueName = Topology.CONTROL_QUEUE + ".orchestrator-status." + id;
        Set<String> bindings = Set.of(
            controllerStatusPattern("status-full"),
            controllerStatusPattern("status-delta")
        );
        return List.of(new QueueDescriptor(queueName, bindings, ExchangeScope.CONTROL));
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
}
