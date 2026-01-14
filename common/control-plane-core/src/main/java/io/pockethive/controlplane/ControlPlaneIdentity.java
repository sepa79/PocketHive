package io.pockethive.controlplane;

import java.util.Objects;

/**
 * Identifies the current participant on the control plane for self-filtering decisions.
 */
public record ControlPlaneIdentity(String swarmId, String swarmInstanceId, String role, String instanceId) {

    public ControlPlaneIdentity {
        swarmId = normalise(swarmId);
        swarmInstanceId = normalise(swarmInstanceId);
        role = normalise(role);
        instanceId = normalise(instanceId);
    }

    private static String normalise(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    public boolean matchesRole(String candidate) {
        return Objects.equals(role, normalise(candidate));
    }

    public boolean matchesInstance(String candidateRole, String candidateInstance) {
        return matchesRole(candidateRole) && Objects.equals(instanceId, normalise(candidateInstance));
    }
}
