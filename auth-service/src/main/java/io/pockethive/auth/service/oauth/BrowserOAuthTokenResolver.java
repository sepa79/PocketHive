package io.pockethive.auth.service.oauth;

import io.pockethive.auth.contract.AuthenticatedUserDto;
import io.pockethive.auth.contract.PocketHiveBrowserAuth;
import io.pockethive.auth.service.config.AuthServiceProperties;
import io.pockethive.auth.service.service.InMemoryUserStore;
import java.util.List;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * Responsibility: Resolve web OAuth bearer identity from canonical live authorization state.
 * Must not: Cache tokens, accept MCP audiences or grant product permissions.
 * Contract: RESP-BROWSER-SSO — docs/architecture/runtime-responsibilities.md#resp-browser-sso.
 */
@Component
public final class BrowserOAuthTokenResolver {
    private final OAuth2AuthorizationService authorizations;
    private final RegisteredClientRepository clients;
    private final InMemoryUserStore users;
    private final AuthServiceProperties properties;

    public BrowserOAuthTokenResolver(OAuth2AuthorizationService authorizations, RegisteredClientRepository clients,
                                      InMemoryUserStore users, AuthServiceProperties properties) {
        this.authorizations = authorizations;
        this.clients = clients;
        this.users = users;
        this.properties = properties;
    }

    public AuthenticatedUserDto resolve(String token) {
        var authorization = authorizations.findByToken(token, OAuth2TokenType.ACCESS_TOKEN);
        if (authorization == null || authorization.getAccessToken() == null
            || !authorization.getAccessToken().isActive()) throw rejected();
        var client = clients.findById(authorization.getRegisteredClientId());
        var claims = authorization.getAccessToken().getClaims();
        if (client == null || !BrowserOAuthClients.isBrowser(client)
            || !Set.of(PocketHiveBrowserAuth.SCOPE).equals(authorization.getAuthorizedScopes())
            || claims == null || !List.of(PocketHiveBrowserAuth.RESOURCE).equals(claims.get("aud"))) throw rejected();
        return users.findByUsername(authorization.getPrincipalName()).filter(user -> user.active())
            .orElseThrow(BrowserOAuthTokenResolver::rejected).toDto(properties.getProvider());
    }

    private static ResponseStatusException rejected() {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid or expired browser session");
    }
}
