package io.pockethive.auth.contract;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.net.URI;

/**
 * Responsibility: Represent a structurally validated device authorization response from PH.
 * Must not: Poll, store codes or decide the consumer's transport permission.
 * Contract: RESP-BROWSER-DEVICE-LOGIN — docs/architecture/runtime-responsibilities.md#resp-browser-device-login.
 */
public record DeviceAuthorizationDto(
    @JsonProperty("device_code") String deviceCode,
    @JsonProperty("user_code") String userCode,
    @JsonProperty("verification_uri") URI verificationUri,
    @JsonProperty("verification_uri_complete") URI verificationUriComplete,
    @JsonProperty("expires_in") int expiresIn,
    @JsonInclude(JsonInclude.Include.NON_NULL) Integer interval
) {
    public DeviceAuthorizationDto {
        if (deviceCode == null || deviceCode.isBlank() || deviceCode.length() > 4096
            || userCode == null || userCode.isBlank() || userCode.length() > 128
            || expiresIn <= 0 || interval != null && interval <= 0
            || !PublicEndpointTransportPolicy.allows(verificationUri, true)
            || verificationUriComplete == null || verificationUriComplete.getUserInfo() != null
            || verificationUriComplete.getFragment() != null
            || !verificationUri.resolve("/").equals(verificationUriComplete.resolve("/"))
            || !verificationUri.getPath().equals(verificationUriComplete.getPath())) {
            throw new IllegalArgumentException("Invalid PH device authorization response");
        }
    }

    @Override public String toString() { return "DeviceAuthorizationDto[redacted]"; }
}
