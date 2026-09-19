package io.pockethive.auth.service.oauth;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenClaimAccessor;

/**
 * Responsibility: Carry the generated PocketHive access-token value and claims.
 * Must not: Bypass canonical scope policy, client authentication, or Spring Authorization Server contracts.
 * Contract: docs/architecture/AUTH_SERVICE_API_SPEC.md and docs/AUTH-BEHAVIOR.md.
 */

public final class PocketHiveAccessToken extends OAuth2AccessToken implements OAuth2TokenClaimAccessor {
    private final Map<String, Object> claims;

    public PocketHiveAccessToken(String value, Instant issuedAt, Instant expiresAt, Set<String> scopes,
                                 Map<String, Object> claims) {
        super(TokenType.BEARER, value, issuedAt, expiresAt, scopes);
        this.claims = Map.copyOf(claims);
    }

    @Override
    public Map<String, Object> getClaims() {
        return claims;
    }
}
