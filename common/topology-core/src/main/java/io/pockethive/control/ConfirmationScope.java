package io.pockethive.control;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.util.Objects;

/**
 * Identifies the swarm/role/instance addressed by a confirmation.
 */
public record ConfirmationScope(String swarmId, String role, String instance) {

    public static final ConfirmationScope EMPTY = new ConfirmationScope(null, null, null);

    public ConfirmationScope {
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

    public static ConfirmationScope forSwarm(String swarmId) {
        return new ConfirmationScope(swarmId, null, null);
    }

    public static ConfirmationScope forInstance(String swarmId, String role, String instance) {
        return new ConfirmationScope(swarmId, role, instance);
    }

    @JsonIgnore
    public boolean isEmpty() {
        return Objects.isNull(swarmId) && Objects.isNull(role) && Objects.isNull(instance);
    }
}

