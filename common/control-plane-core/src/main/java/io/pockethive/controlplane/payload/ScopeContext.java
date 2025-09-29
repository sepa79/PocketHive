package io.pockethive.controlplane.payload;

import io.pockethive.control.ConfirmationScope;
import java.util.Objects;

/**
 * Provides the confirmation scope for payload factories.
 */
public record ScopeContext(ConfirmationScope scope) {

    public ScopeContext {
        Objects.requireNonNull(scope, "scope");
    }

    public static ScopeContext of(ConfirmationScope scope) {
        return new ScopeContext(scope);
    }

    public static ScopeContext fromRole(RoleContext role) {
        Objects.requireNonNull(role, "role");
        return new ScopeContext(role.toScope());
    }
}
