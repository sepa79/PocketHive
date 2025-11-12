package io.pockethive.controlplane.spring;

import io.pockethive.controlplane.topology.ControlPlaneTopologyDescriptor;
import io.pockethive.controlplane.topology.ControlPlaneTopologySettings;
import io.pockethive.controlplane.topology.OrchestratorControlPlaneTopologyDescriptor;
import io.pockethive.controlplane.topology.ScenarioManagerTopologyDescriptor;
import io.pockethive.controlplane.topology.SwarmControllerControlPlaneTopologyDescriptor;
import io.pockethive.controlplane.topology.WorkerControlPlaneTopologyDescriptor;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * Utility factory that maps role identifiers to their control-plane descriptor implementations.
 */
public final class ControlPlaneTopologyDescriptorFactory {

    private ControlPlaneTopologyDescriptorFactory() {
    }

    private static final Map<String, Function<ControlPlaneTopologySettings, ControlPlaneTopologyDescriptor>> MANAGER_DESCRIPTORS = Map.of(
        "orchestrator", OrchestratorControlPlaneTopologyDescriptor::new,
        "swarm-controller", SwarmControllerControlPlaneTopologyDescriptor::new,
        "scenario-manager", settings -> new ScenarioManagerTopologyDescriptor()
    );

    public static ControlPlaneTopologyDescriptor forWorkerRole(String role, ControlPlaneTopologySettings settings) {
        String normalised = normalise(role);
        ControlPlaneTopologySettings resolved = Objects.requireNonNull(settings, "settings");
        return new WorkerControlPlaneTopologyDescriptor(normalised, resolved);
    }

    public static ControlPlaneTopologyDescriptor forManagerRole(String role, ControlPlaneTopologySettings settings) {
        return createDescriptor(role, MANAGER_DESCRIPTORS, "manager", settings);
    }

    public static boolean isWorkerRole(String role) {
        return !containsRole(role, MANAGER_DESCRIPTORS);
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
                                                                   Map<String, Function<ControlPlaneTopologySettings, ControlPlaneTopologyDescriptor>> descriptors,
                                                                   String type,
                                                                   ControlPlaneTopologySettings settings) {
        String normalised = normalise(role);
        Function<ControlPlaneTopologySettings, ControlPlaneTopologyDescriptor> supplier = descriptors.get(normalised);
        if (supplier == null) {
            throw new IllegalArgumentException("Unsupported " + type + " role: " + role);
        }
        ControlPlaneTopologySettings resolved = Objects.requireNonNull(settings, "settings");
        return supplier.apply(resolved);
    }

    private static boolean containsRole(String role, Map<String, ?> descriptors) {
        String normalised = normalise(role);
        return descriptors.containsKey(normalised);
    }
}
