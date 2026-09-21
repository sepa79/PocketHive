package io.pockethive.auth.service.oauth;

import io.pockethive.auth.contract.AuthenticatedUserDto;
import io.pockethive.auth.service.config.AuthServiceProperties;
import io.pockethive.auth.service.domain.StoredUser;
import io.pockethive.auth.service.service.InMemoryUserStore;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenGenerator;

/**
 * Responsibility: Generate audience-bound PocketHive access tokens from canonical authorization state.
 * Must not: Bypass canonical scope policy, client authentication, or Spring Authorization Server contracts.
 * Contract: docs/architecture/AUTH_SERVICE_API_SPEC.md and docs/AUTH-BEHAVIOR.md.
 */

public final class PocketHiveAccessTokenGenerator implements OAuth2TokenGenerator<OAuth2AccessToken> {
    private final AuthServiceProperties properties;
    private final InMemoryUserStore users;
    private final SecureRandom random = new SecureRandom();

    public PocketHiveAccessTokenGenerator(AuthServiceProperties properties, InMemoryUserStore users) {
        this.properties = properties;
        this.users = users;
    }

    @Override
    public OAuth2AccessToken generate(OAuth2TokenContext context) {
        if (!OAuth2TokenType.ACCESS_TOKEN.equals(context.getTokenType())) {
            return null;
        }
        String principalName = context.getAuthorization().getPrincipalName();
        StoredUser stored = users.findByUsername(principalName)
            .filter(StoredUser::active)
            .orElseThrow(PocketHiveAccessTokenGenerator::invalidGrant);
        Set<String> effectiveScopes = context.getAuthorizedScopes();
        if (!McpScopeAuthorizationValidator.allowedScopes(stored.grants()).containsAll(effectiveScopes)) {
            throw invalidGrant();
        }
        AuthenticatedUserDto principal = stored.toDto(properties.getProvider());
        Instant issuedAt = Instant.now();
        Instant expiresAt = issuedAt.plus(context.getRegisteredClient().getTokenSettings().getAccessTokenTimeToLive());
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("iss", properties.getOauth().getIssuer().toString());
        claims.put("sub", stored.id().toString());
        claims.put("aud", List.of(properties.getOauth().getResource().toString()));
        claims.put("client_id", context.getRegisteredClient().getClientId());
        claims.put("username", stored.username());
        claims.put("scope", String.join(" ", effectiveScopes));
        claims.put("iat", issuedAt);
        claims.put("exp", expiresAt);
        claims.put("principal", principal);
        byte[] entropy = new byte[32];
        random.nextBytes(entropy);
        String token = "phmcp_" + Base64.getUrlEncoder().withoutPadding().encodeToString(entropy);
        return new PocketHiveAccessToken(token, issuedAt, expiresAt, effectiveScopes, claims);
    }

    private static OAuth2AuthenticationException invalidGrant() {
        return new OAuth2AuthenticationException(new OAuth2Error(
            OAuth2ErrorCodes.INVALID_GRANT, "Principal is inactive or no longer grants the authorized scopes", null));
    }
}
