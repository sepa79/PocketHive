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

/** Issues rotating bearer material only for the exact least-privilege companion base session. */
public final class PocketHiveRefreshTokenGenerator implements OAuth2TokenGenerator<OAuth2RefreshToken> {
    private static final Set<String> BASE_SESSION_SCOPES = Set.of(
        PocketHiveMcpScopes.DISCOVER, PocketHiveMcpScopes.READ);
    private final SecureRandom random = new SecureRandom();

    @Override
    public OAuth2RefreshToken generate(OAuth2TokenContext context) {
        if (!OAuth2TokenType.REFRESH_TOKEN.equals(context.getTokenType())
            || !BASE_SESSION_SCOPES.equals(context.getAuthorizedScopes())) {
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
