package io.pockethive.control;

import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * Identifies the swarm/role/instance addressed by a confirmation.
 * Use the literal {@link #ALL} for fan-out; missing and blank values are invalid.
 */
public record ConfirmationScope(String swarmId, String role, String instance) {

    public static final String ALL = ControlScope.ALL;

    public static final ConfirmationScope EMPTY = new ConfirmationScope(ALL, ALL, ALL);

    public ConfirmationScope {
        swarmId = ControlScope.requireSegment("confirmationScope.swarmId", swarmId);
        role = ControlScope.requireSegment("confirmationScope.role", role);
        instance = ControlScope.requireSegment("confirmationScope.instance", instance);
    }

    public static ConfirmationScope forSwarm(String swarmId) {
        return new ConfirmationScope(swarmId, ALL, ALL);
    }

    public static ConfirmationScope forInstance(String swarmId, String role, String instance) {
        return new ConfirmationScope(swarmId, role, instance);
    }

    @JsonIgnore
    public boolean isEmpty() {
        return ControlScope.isAll(swarmId) && ControlScope.isAll(role) && ControlScope.isAll(instance);
    }
}
