package io.pockethive.auth.service.oauth;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationCode;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenGenerator;

public final class PocketHiveAuthorizationCodeGenerator implements OAuth2TokenGenerator<OAuth2AuthorizationCode> {
    private final SecureRandom random = new SecureRandom();

    @Override
    public OAuth2AuthorizationCode generate(OAuth2TokenContext context) {
        if (!OAuth2ParameterNames.CODE.equals(context.getTokenType().getValue())) {
            return null;
        }
        byte[] entropy = new byte[32];
        random.nextBytes(entropy);
        Instant issuedAt = Instant.now();
        return new OAuth2AuthorizationCode(
            Base64.getUrlEncoder().withoutPadding().encodeToString(entropy),
            issuedAt,
            issuedAt.plus(context.getRegisteredClient().getTokenSettings().getAuthorizationCodeTimeToLive()));
    }
}
