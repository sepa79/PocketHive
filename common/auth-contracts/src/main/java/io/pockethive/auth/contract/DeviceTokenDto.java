package io.pockethive.auth.contract;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Responsibility: Represent the first-party device access-token result.
 * Must not: Persist tokens, refresh sessions or decide access.
 * Contract: docs/architecture/BROWSER_DEVICE_LOGIN.md.
 */
public record DeviceTokenDto(
    @JsonProperty("access_token") String accessToken,
    @JsonProperty("token_type") String tokenType,
    @JsonProperty("expires_in") int expiresIn,
    String scope
) {
    public DeviceTokenDto {
        if (accessToken == null || !accessToken.startsWith(PocketHiveBrowserAuth.TOKEN_PREFIX)
            || accessToken.length() > 16_000 || !"Bearer".equals(tokenType) || expiresIn <= 0
            || !PocketHiveBrowserAuth.SCOPE.equals(scope)) {
            throw new IllegalArgumentException("Invalid PH device token response");
        }
    }

    @Override public String toString() { return "DeviceTokenDto[redacted]"; }
}
