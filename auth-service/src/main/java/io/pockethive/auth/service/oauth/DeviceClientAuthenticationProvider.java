package io.pockethive.auth.service.oauth;

import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;

/**
 * Responsibility: Authenticate only explicitly registered first-party public device clients.
 * Must not: Trust arbitrary client IDs, authorize user scopes or mutate token state.
 * Contract: docs/architecture/BROWSER_DEVICE_LOGIN.md.
 */
final class DeviceClientAuthenticationProvider implements AuthenticationProvider {
    private final RegisteredClientRepository clients;

    DeviceClientAuthenticationProvider(RegisteredClientRepository clients) { this.clients = clients; }

    @Override
    public Authentication authenticate(Authentication authentication) {
        OAuth2ClientAuthenticationToken candidate = (OAuth2ClientAuthenticationToken) authentication;
        if (!Boolean.TRUE.equals(candidate.getAdditionalParameters().get(DeviceClientAuthenticationConverter.MARKER))) return null;
        var client = clients.findByClientId(candidate.getPrincipal().toString());
        if (!BrowserOAuthClients.isDevice(client)) {
            throw new OAuth2AuthenticationException(OAuth2ErrorCodes.INVALID_CLIENT);
        }
        return new OAuth2ClientAuthenticationToken(client, ClientAuthenticationMethod.NONE, null);
    }

    @Override
    public boolean supports(Class<?> type) { return OAuth2ClientAuthenticationToken.class.isAssignableFrom(type); }
}
