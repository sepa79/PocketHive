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

/** Resolves the one declared companion intent to the principal's current grant ceiling before consent. */
public final class PocketHiveCompanionAuthorizationRequestConverter implements AuthenticationConverter {
    private final OAuth2AuthorizationCodeRequestAuthenticationConverter delegate =
        new OAuth2AuthorizationCodeRequestAuthenticationConverter();
    private final String companionClientId;
    private final InMemoryUserStore users;

    public PocketHiveCompanionAuthorizationRequestConverter(String companionClientId, InMemoryUserStore users) {
        if (companionClientId == null || companionClientId.isBlank()) {
            throw new IllegalArgumentException("OAUTH_COMPANION_CLIENT_ID_REQUIRED");
        }
        this.companionClientId = companionClientId;
        this.users = java.util.Objects.requireNonNull(users, "users");
    }

    @Override
    public Authentication convert(HttpServletRequest request) {
        Authentication converted = delegate.convert(request);
        if (!(converted instanceof OAuth2AuthorizationCodeRequestAuthenticationToken candidate)
            || !companionClientId.equals(candidate.getClientId())
            || !PocketHiveMcpScopes.COMPANION.equals(candidate.getScopes())) {
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
        grantedScopes.retainAll(PocketHiveMcpScopes.COMPANION);
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
