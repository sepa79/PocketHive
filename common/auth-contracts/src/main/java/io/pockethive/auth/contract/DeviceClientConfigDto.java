package io.pockethive.auth.contract;

import java.net.URI;

/**
 * Responsibility: Represent the canonical public device-client logout address.
 * Must not: Infer browser origins, decide transport permission or manage sessions.
 * Contract: RESP-BROWSER-DEVICE-LOGIN — docs/architecture/runtime-responsibilities.md#resp-browser-device-login.
 */
public record DeviceClientConfigDto(URI logoutUri) {
    public DeviceClientConfigDto {
        if (logoutUri == null || logoutUri.getFragment() != null || logoutUri.getUserInfo() != null
            || !PublicEndpointTransportPolicy.allows(logoutUri.resolve("/"), true)
            || !logoutUri.getPath().endsWith(PocketHiveDeviceAuth.LOGOUT_PATH)) {
            throw new IllegalArgumentException("Invalid PH logout configuration");
        }
    }
}
