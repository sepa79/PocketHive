package io.pockethive.control;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.util.Objects;

/**
 * Identifies the swarm/role/instance addressed by a confirmation.
 * Use {@link #ALL} for fan-out; values are never {@code null}.
 */
public record ConfirmationScope(String swarmId, String role, String instance) {

    public static final String ALL = ControlScope.ALL;

    public static final ConfirmationScope EMPTY = new ConfirmationScope(ALL, ALL, ALL);

    public ConfirmationScope {
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
        if (ControlScope.isAll(trimmed)) {
            return ALL;
        }
        return trimmed;
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
