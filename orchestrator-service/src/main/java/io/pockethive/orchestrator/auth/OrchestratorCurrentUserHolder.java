package io.pockethive.orchestrator.auth;

import io.pockethive.auth.contract.AuthenticatedUserDto;

public final class OrchestratorCurrentUserHolder {
    private static final ThreadLocal<AuthenticatedUserDto> CURRENT = new ThreadLocal<>();

    private OrchestratorCurrentUserHolder() {
    }

    public static void set(AuthenticatedUserDto user) {
        CURRENT.set(user);
    }

    public static AuthenticatedUserDto get() {
        return CURRENT.get();
    }

    public static void clear() {
        CURRENT.remove();
    }
}
