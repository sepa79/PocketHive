package io.pockethive.auth.contract;

import java.net.URI;

/**
 * Responsibility: Represent the canonical public device-client logout address.
 * Must not: Infer browser origins, redirect to an app or manage sessions.
 * Contract: docs/architecture/BROWSER_DEVICE_LOGIN.md#central-logout-without-callbacks.
 */
public record DeviceClientConfigDto(URI logoutUri) {
    public DeviceClientConfigDto {
        if (logoutUri == null || logoutUri.getFragment() != null || logoutUri.getUserInfo() != null
            || !PublicEndpointTransportPolicy.allows(logoutUri.resolve("/"), false)
            || !logoutUri.getPath().endsWith(PocketHiveDeviceAuth.LOGOUT_PATH)) {
            throw new IllegalArgumentException("Invalid PH logout configuration");
        }
    }
}
