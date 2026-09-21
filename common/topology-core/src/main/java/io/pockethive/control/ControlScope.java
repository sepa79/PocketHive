package io.pockethive.control;

import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * Canonical scope tuple used by control-plane envelopes.
 * <p>
 * Describes the swarm, role and instance that a message is about (the subject),
 * independently of the component that emitted the message ({@code origin}).
 * Use the literal {@link #ALL} for fan-out; missing and blank values are invalid.
 */
public record ControlScope(String swarmId, String role, String instance) {

    public static final String ALL = "ALL";

    public static final ControlScope EMPTY = new ControlScope(ALL, ALL, ALL);

    public static boolean isAll(String value) {
        return ALL.equals(value);
    }

    public ControlScope {
        swarmId = requireSegment("scope.swarmId", swarmId);
        role = requireSegment("scope.role", role);
        instance = requireSegment("scope.instance", instance);
    }

    /** Validates one explicit scope or routing segment without introducing a wildcard default. */
    public static String requireSegment(String field, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank; use ALL for fan-out");
        }
        String trimmed = value.trim();
        if (trimmed.equalsIgnoreCase(ALL) && !ALL.equals(trimmed)) {
            throw new IllegalArgumentException(field + " must use the literal ALL for fan-out");
        }
        if (ALL.equals(trimmed)) {
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
