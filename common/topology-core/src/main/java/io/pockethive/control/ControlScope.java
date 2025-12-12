package io.pockethive.control;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.Objects;

/**
 * Canonical scope tuple used by control-plane envelopes.
 * <p>
 * Describes the swarm, role and instance that a message is about (the subject),
 * independently of the component that emitted the message ({@code origin}).
 */
public record ControlScope(String swarmId, String role, String instance) {

    public static final ControlScope EMPTY = new ControlScope(null, null, null);

    public ControlScope {
        swarmId = normalize(swarmId);
        role = normalize(role);
        instance = normalize(instance);
    }

    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    public static ControlScope forSwarm(String swarmId) {
        return new ControlScope(swarmId, null, null);
    }

    public static ControlScope forRole(String swarmId, String role) {
        return new ControlScope(swarmId, role, null);
    }

    public static ControlScope forInstance(String swarmId, String role, String instance) {
        return new ControlScope(swarmId, role, instance);
    }

    @JsonIgnore
    public boolean isEmpty() {
        return Objects.isNull(swarmId) && Objects.isNull(role) && Objects.isNull(instance);
    }
}
