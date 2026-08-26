package io.pockethive.auth.service.oauth;

import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;

/**
 * Responsibility: Authenticate registered public-session clients without a client secret.
 * Must not: Bypass canonical scope policy, client authentication, or Spring Authorization Server contracts.
 * Contract: docs/architecture/AUTH_SERVICE_API_SPEC.md and docs/AUTH-BEHAVIOR.md.
 */

/** Validates an explicitly marked refresh/revocation request against the registered public client. */
final class PocketHivePublicSessionClientAuthenticationProvider implements AuthenticationProvider {
    private final RegisteredClientRepository clients;

    PocketHivePublicSessionClientAuthenticationProvider(RegisteredClientRepository clients) {
        this.clients = clients;
    }

    @Override
    public Authentication authenticate(Authentication authentication) {
        OAuth2ClientAuthenticationToken candidate = (OAuth2ClientAuthenticationToken) authentication;
        if (!Boolean.TRUE.equals(candidate.getAdditionalParameters().get(
            PocketHivePublicSessionClientAuthenticationConverter.SESSION_CLIENT_MARKER))) {
            return null;
        }
        RegisteredClient client = clients.findByClientId(candidate.getPrincipal().toString());
        if (client == null
            || !client.getClientAuthenticationMethods().contains(ClientAuthenticationMethod.NONE)
            || !client.getAuthorizationGrantTypes().contains(AuthorizationGrantType.REFRESH_TOKEN)) {
            throw new OAuth2AuthenticationException(OAuth2ErrorCodes.INVALID_CLIENT);
        }
        return new OAuth2ClientAuthenticationToken(client, ClientAuthenticationMethod.NONE, null);
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return OAuth2ClientAuthenticationToken.class.isAssignableFrom(authentication);
    }
}
