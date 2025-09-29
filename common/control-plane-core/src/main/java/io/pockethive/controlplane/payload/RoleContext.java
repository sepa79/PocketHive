package io.pockethive.controlplane.payload;

import io.pockethive.control.ConfirmationScope;
import java.util.Objects;

/**
 * Identifies the control-plane role emitting payloads.
 */
public record RoleContext(String swarmId, String role, String instanceId) {

    public RoleContext {
        swarmId = requireNonBlank("swarmId", swarmId);
        role = requireNonBlank("role", role);
        instanceId = requireNonBlank("instanceId", instanceId);
    }

    private static String requireNonBlank(String field, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }

    public ConfirmationScope toScope() {
        return new ConfirmationScope(swarmId, role, instanceId);
    }

    public static RoleContext fromIdentity(io.pockethive.controlplane.ControlPlaneIdentity identity) {
        Objects.requireNonNull(identity, "identity");
        return new RoleContext(identity.swarmId(), identity.role(), identity.instanceId());
    }
}
