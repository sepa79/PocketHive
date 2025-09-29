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

/**
 * Utility factory that maps role identifiers to their control-plane descriptor implementations.
 */
public final class ControlPlaneTopologyDescriptorFactory {

    private ControlPlaneTopologyDescriptorFactory() {
    }

    public static ControlPlaneTopologyDescriptor forWorkerRole(String role) {
        String normalised = normalise(role);
        return switch (normalised) {
            case "generator" -> new GeneratorControlPlaneTopologyDescriptor();
            case "moderator" -> new ModeratorControlPlaneTopologyDescriptor();
            case "processor" -> new ProcessorControlPlaneTopologyDescriptor();
            case "postprocessor" -> new PostProcessorControlPlaneTopologyDescriptor();
            case "trigger" -> new TriggerControlPlaneTopologyDescriptor();
            default -> throw new IllegalArgumentException("Unsupported worker role: " + role);
        };
    }

    public static ControlPlaneTopologyDescriptor forManagerRole(String role) {
        String normalised = normalise(role);
        return switch (normalised) {
            case "orchestrator" -> new OrchestratorControlPlaneTopologyDescriptor();
            case "swarm-controller" -> new SwarmControllerControlPlaneTopologyDescriptor();
            case "scenario-manager" -> new ScenarioManagerTopologyDescriptor();
            default -> throw new IllegalArgumentException("Unsupported manager role: " + role);
        };
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
}
