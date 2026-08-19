package io.pockethive.auth.service.oauth;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenClaimAccessor;

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
