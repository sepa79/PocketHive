package io.pockethive.auth.service.oauth;

import io.pockethive.auth.contract.PocketHiveMcpScopes;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Set;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenGenerator;

/**
 * Responsibility: Generate rotating PocketHive refresh tokens for renewable public sessions.
 * Must not: Bypass canonical scope policy, client authentication, or Spring Authorization Server contracts.
 * Contract: docs/architecture/AUTH_SERVICE_API_SPEC.md and docs/AUTH-BEHAVIOR.md.
 */

/** Issues rotating bearer material only for bounded interactive non-cleanup grants. */
public final class PocketHiveRefreshTokenGenerator implements OAuth2TokenGenerator<OAuth2RefreshToken> {
    private final SecureRandom random = new SecureRandom();

    @Override
    public OAuth2RefreshToken generate(OAuth2TokenContext context) {
        Set<String> scopes = context.getAuthorizedScopes();
        if (!OAuth2TokenType.REFRESH_TOKEN.equals(context.getTokenType())
            || !scopes.contains(PocketHiveMcpScopes.DISCOVER)
            || !scopes.contains(PocketHiveMcpScopes.READ)
            || !PocketHiveMcpScopes.COMPANION.containsAll(scopes)) {
            return null;
        }
        byte[] entropy = new byte[64];
        random.nextBytes(entropy);
        Instant issuedAt = Instant.now();
        Instant expiresAt = issuedAt.plus(
            context.getRegisteredClient().getTokenSettings().getRefreshTokenTimeToLive());
        return new OAuth2RefreshToken(
            "phrfr_" + Base64.getUrlEncoder().withoutPadding().encodeToString(entropy),
            issuedAt,
            expiresAt);
    }
}
