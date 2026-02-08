package io.pockethive.control;

import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * Canonical scope tuple used by control-plane envelopes.
 * <p>
 * Describes the swarm, role and instance that a message is about (the subject),
 * independently of the component that emitted the message ({@code origin}).
 * Use {@link #ALL} for fan-out; values are never {@code null}.
 */
public record ControlScope(String swarmId, String role, String instance) {

    public static final String ALL = "ALL";

    public static final ControlScope EMPTY = new ControlScope(ALL, ALL, ALL);

    public static boolean isAll(String value) {
        return value != null && value.equalsIgnoreCase(ALL);
    }

    public ControlScope {
        swarmId = normalize(swarmId);
        role = normalize(role);
        instance = normalize(instance);
    }

    private static String normalize(String value) {
        if (value == null) {
            return ALL;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return ALL;
        }
        if (isAll(trimmed)) {
            return ALL;
        }
        return trimmed;
    }

    public static ControlScope forSwarm(String swarmId) {
        return new ControlScope(swarmId, ALL, ALL);
    }

    public static ControlScope forRole(String swarmId, String role) {
        return new ControlScope(swarmId, role, ALL);
    }

    public static ControlScope forInstance(String swarmId, String role, String instance) {
        return new ControlScope(swarmId, role, instance);
    }

    @JsonIgnore
    public boolean isEmpty() {
        return isAll(swarmId) && isAll(role) && isAll(instance);
    }
}
