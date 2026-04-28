package io.pockethive.networkproxy.auth;

import io.pockethive.auth.contract.AuthenticatedUserDto;

public final class NetworkProxyManagerCurrentUserHolder {
    private static final ThreadLocal<AuthenticatedUserDto> CURRENT_USER = new ThreadLocal<>();

    private NetworkProxyManagerCurrentUserHolder() {
    }

    public static void set(AuthenticatedUserDto user) {
        CURRENT_USER.set(user);
    }

    public static AuthenticatedUserDto get() {
        return CURRENT_USER.get();
    }

    public static void clear() {
        CURRENT_USER.remove();
    }
}
