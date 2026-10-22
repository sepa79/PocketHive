package io.pockethive.auth.service.oauth;

import io.pockethive.auth.contract.PocketHiveBrowserAuth;
import io.pockethive.auth.service.service.InMemoryUserStore;
import java.util.Set;
import java.util.function.Consumer;
import org.springframework.security.core.Authentication;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationContext;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationValidator;

/**
 * Responsibility: Dispatch registered browser identity and MCP authorization policy.
 * Must not: Infer client kinds from requested scopes or grant product permissions.
 * Contract: RESP-BROWSER-SSO — docs/architecture/runtime-responsibilities.md#resp-browser-sso.
 */
public final class PocketHiveScopeAuthorizationValidator
    implements Consumer<OAuth2AuthorizationCodeRequestAuthenticationContext> {
    private final OAuthPrincipalLookup principals;
    private final McpScopeAuthorizationValidator mcp;
    private final OAuth2AuthorizationCodeRequestAuthenticationValidator defaults =
        new OAuth2AuthorizationCodeRequestAuthenticationValidator();

    public PocketHiveScopeAuthorizationValidator(InMemoryUserStore users) {
        this.principals = new OAuthPrincipalLookup(users);
        mcp = new McpScopeAuthorizationValidator(users);
    }

    @Override
    public void accept(OAuth2AuthorizationCodeRequestAuthenticationContext context) {
        if (!BrowserOAuthClients.isBrowser(context.getRegisteredClient())) {
            mcp.accept(context);
            return;
        }
        OAuth2AuthorizationCodeRequestAuthenticationToken request = context.getAuthentication();
        if (!context.getRegisteredClient().getRedirectUris().contains(request.getRedirectUri())) {
            throw new OAuth2AuthenticationException(OAuth2ErrorCodes.INVALID_REQUEST);
        }
        defaults.accept(context);
        if (!Set.of(PocketHiveBrowserAuth.SCOPE).equals(request.getScopes())) {
            throw new OAuth2AuthenticationException(OAuth2ErrorCodes.INVALID_SCOPE);
        }
        Authentication principal = SecurityContextHolder.getContext().getAuthentication();
        if (principal != null && principal.isAuthenticated() && !(principal instanceof AnonymousAuthenticationToken)
            && principals.findActive(principal.getName()).isEmpty()) {
            throw new OAuth2AuthenticationException(OAuth2ErrorCodes.ACCESS_DENIED);
        }
    }
}
