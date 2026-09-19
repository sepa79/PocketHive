package io.pockethive.auth.service.oauth;

import io.pockethive.auth.contract.PocketHiveMcpScopes;
import io.pockethive.auth.service.service.InMemoryUserStore;
import jakarta.servlet.http.HttpServletRequest;
import java.util.HashSet;
import java.util.Set;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.web.authentication.OAuth2AuthorizationCodeRequestAuthenticationConverter;
import org.springframework.security.web.authentication.AuthenticationConverter;

/**
 * Responsibility: Convert interactive authorization requests while preserving principal validation.
 * Must not: Bypass canonical scope policy, client authentication, or Spring Authorization Server contracts.
 * Contract: docs/architecture/AUTH_SERVICE_API_SPEC.md and docs/AUTH-BEHAVIOR.md.
 */

/** Narrows any declared interactive MCP intent to the principal's current grant ceiling. */
public final class PocketHiveInteractiveAuthorizationRequestConverter implements AuthenticationConverter {
    private final OAuth2AuthorizationCodeRequestAuthenticationConverter delegate =
        new OAuth2AuthorizationCodeRequestAuthenticationConverter();
    private final InMemoryUserStore users;

    public PocketHiveInteractiveAuthorizationRequestConverter(InMemoryUserStore users) {
        this.users = java.util.Objects.requireNonNull(users, "users");
    }

    @Override
    public Authentication convert(HttpServletRequest request) {
        Authentication converted = delegate.convert(request);
        if (!(converted instanceof OAuth2AuthorizationCodeRequestAuthenticationToken candidate)
            || candidate.getScopes().isEmpty()
            || !PocketHiveMcpScopes.COMPANION.containsAll(candidate.getScopes())) {
            return converted;
        }
        Authentication principal = (Authentication) candidate.getPrincipal();
        if (!principal.isAuthenticated() || principal instanceof AnonymousAuthenticationToken) {
            return candidate;
        }
        return users.findByUsername(principal.getName())
            .filter(user -> user.active())
            .<Authentication>map(user -> narrowed(candidate,
                McpScopeAuthorizationValidator.allowedScopes(user.grants())))
            .orElse(candidate);
    }

    private static OAuth2AuthorizationCodeRequestAuthenticationToken narrowed(
        OAuth2AuthorizationCodeRequestAuthenticationToken candidate,
        Set<String> allowedScopes
    ) {
        Set<String> grantedScopes = new HashSet<>(allowedScopes);
        grantedScopes.retainAll(candidate.getScopes());
        return new OAuth2AuthorizationCodeRequestAuthenticationToken(
            candidate.getAuthorizationUri(),
            candidate.getClientId(),
            (Authentication) candidate.getPrincipal(),
            candidate.getRedirectUri(),
            candidate.getState(),
            Set.copyOf(grantedScopes),
            candidate.getAdditionalParameters());
    }
}
