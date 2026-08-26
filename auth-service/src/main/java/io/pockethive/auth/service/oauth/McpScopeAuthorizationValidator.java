package io.pockethive.auth.service.oauth;

import io.pockethive.auth.contract.AuthGrantDto;
import io.pockethive.auth.contract.AuthProduct;
import io.pockethive.auth.contract.PocketHiveMcpScopes;
import io.pockethive.auth.contract.PocketHivePermissionIds;
import io.pockethive.auth.service.domain.StoredUser;
import io.pockethive.auth.service.service.InMemoryUserStore;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationContext;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationValidator;

/**
 * Responsibility: Validate requested MCP scopes against the canonical principal grants.
 * Must not: Bypass canonical scope policy, client authentication, or Spring Authorization Server contracts.
 * Contract: docs/architecture/AUTH_SERVICE_API_SPEC.md and docs/AUTH-BEHAVIOR.md.
 */

public final class McpScopeAuthorizationValidator
    implements Consumer<OAuth2AuthorizationCodeRequestAuthenticationContext> {
    private final InMemoryUserStore users;
    private final OAuth2AuthorizationCodeRequestAuthenticationValidator defaults =
        new OAuth2AuthorizationCodeRequestAuthenticationValidator();

    public McpScopeAuthorizationValidator(InMemoryUserStore users) {
        this.users = users;
    }

    @Override
    public void accept(OAuth2AuthorizationCodeRequestAuthenticationContext context) {
        defaults.accept(context);
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return;
        }
        StoredUser user = users.findByUsername(authentication.getName())
            .filter(StoredUser::active)
            .orElseThrow(() -> invalidScope("Authenticated principal is not an active Auth Service user"));
        Set<String> allowed = allowedScopes(user.grants());
        OAuth2AuthorizationCodeRequestAuthenticationToken request = context.getAuthentication();
        if (!allowed.containsAll(request.getScopes())) {
            throw invalidScope("Requested MCP scope is not granted to the principal");
        }
    }

    static Set<String> allowedScopes(java.util.List<AuthGrantDto> grants) {
        Set<String> allowed = new HashSet<>();
        for (AuthGrantDto grant : grants) {
            if (grant.product() != AuthProduct.POCKETHIVE) {
                continue;
            }
            if (PocketHivePermissionIds.ALL.equals(grant.permission())) {
                allowed.addAll(PocketHiveMcpScopes.ALL);
            } else if (PocketHivePermissionIds.VIEW.equals(grant.permission())) {
                allowed.add(PocketHiveMcpScopes.DISCOVER);
                allowed.add(PocketHiveMcpScopes.READ);
            } else if (PocketHivePermissionIds.RUN.equals(grant.permission())) {
                allowed.add(PocketHiveMcpScopes.DISCOVER);
                allowed.add(PocketHiveMcpScopes.READ);
                allowed.add(PocketHiveMcpScopes.OPERATE);
                allowed.add(PocketHiveMcpScopes.AUTHOR);
            }
        }
        return Set.copyOf(allowed);
    }

    private static OAuth2AuthenticationException invalidScope(String description) {
        return new OAuth2AuthenticationException(new OAuth2Error(
            OAuth2ErrorCodes.INVALID_SCOPE, description, null));
    }
}
