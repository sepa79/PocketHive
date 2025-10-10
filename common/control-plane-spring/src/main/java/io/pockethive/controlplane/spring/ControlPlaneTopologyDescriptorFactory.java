package io.pockethive.controlplane.spring;

import io.pockethive.controlplane.topology.ControlPlaneTopologyDescriptor;
import io.pockethive.controlplane.topology.GeneratorControlPlaneTopologyDescriptor;
import io.pockethive.controlplane.topology.ModeratorControlPlaneTopologyDescriptor;
import io.pockethive.controlplane.topology.OrchestratorControlPlaneTopologyDescriptor;
import io.pockethive.controlplane.topology.PostProcessorControlPlaneTopologyDescriptor;
import io.pockethive.controlplane.topology.ProcessorControlPlaneTopologyDescriptor;
import io.pockethive.controlplane.topology.ScenarioManagerTopologyDescriptor;
import io.pockethive.controlplane.topology.SwarmControllerControlPlaneTopologyDescriptor;
import io.pockethive.controlplane.topology.TriggerControlPlaneTopologyDescriptor;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Utility factory that maps role identifiers to their control-plane descriptor implementations.
 */
public final class ControlPlaneTopologyDescriptorFactory {

    private ControlPlaneTopologyDescriptorFactory() {
    }

    private static final Map<String, Supplier<ControlPlaneTopologyDescriptor>> WORKER_DESCRIPTORS = Map.of(
        "generator", GeneratorControlPlaneTopologyDescriptor::new,
        "moderator", ModeratorControlPlaneTopologyDescriptor::new,
        "processor", ProcessorControlPlaneTopologyDescriptor::new,
        "postprocessor", PostProcessorControlPlaneTopologyDescriptor::new,
        "trigger", TriggerControlPlaneTopologyDescriptor::new
    );

    private static final Map<String, Supplier<ControlPlaneTopologyDescriptor>> MANAGER_DESCRIPTORS = Map.of(
        "orchestrator", OrchestratorControlPlaneTopologyDescriptor::new,
        "swarm-controller", SwarmControllerControlPlaneTopologyDescriptor::new,
        "scenario-manager", ScenarioManagerTopologyDescriptor::new
    );

    public static ControlPlaneTopologyDescriptor forWorkerRole(String role) {
        return createDescriptor(role, WORKER_DESCRIPTORS, "worker");
    }

    public static ControlPlaneTopologyDescriptor forManagerRole(String role) {
        return createDescriptor(role, MANAGER_DESCRIPTORS, "manager");
    }

    public static boolean isWorkerRole(String role) {
        return containsRole(role, WORKER_DESCRIPTORS);
    }

    public static boolean isManagerRole(String role) {
        return containsRole(role, MANAGER_DESCRIPTORS);
    }

    private static String normalise(String role) {
        if (role == null) {
            throw new IllegalArgumentException("role must not be null");
        }
        String trimmed = role.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("role must not be blank");
        }
        return trimmed;
    }

    private static ControlPlaneTopologyDescriptor createDescriptor(String role,
                                                                   Map<String, Supplier<ControlPlaneTopologyDescriptor>> descriptors,
                                                                   String type) {
        String normalised = normalise(role);
        Supplier<ControlPlaneTopologyDescriptor> supplier = descriptors.get(normalised);
        if (supplier == null) {
            throw new IllegalArgumentException("Unsupported " + type + " role: " + role);
        }
        return supplier.get();
    }

    private static boolean containsRole(String role, Map<String, Supplier<ControlPlaneTopologyDescriptor>> descriptors) {
        String normalised = normalise(role);
        return descriptors.containsKey(normalised);
    }
}
