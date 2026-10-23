package io.pockethive.auth.service.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.pockethive.auth.contract.AuthGrantDto;
import io.pockethive.auth.contract.AuthProduct;
import io.pockethive.auth.contract.PocketHiveBrowserAuth;
import io.pockethive.auth.contract.PocketHiveDeviceAuth;
import io.pockethive.auth.contract.PocketHivePermissionIds;
import io.pockethive.auth.contract.PocketHiveResourceTypes;
import io.pockethive.auth.contract.UserUpsertRequestDto;
import io.pockethive.auth.service.config.AuthServiceProperties;
import io.pockethive.auth.service.config.AuthServiceUserProperties;
import io.pockethive.auth.service.service.InMemoryUserStore;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.server.authorization.InMemoryOAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.client.InMemoryRegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenContext;
import org.springframework.web.server.ResponseStatusException;

class BrowserOAuthTokenResolverTest {
    private static final UUID SUBJECT = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID OTHER = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final String ORIGINAL_USERNAME = "viewer";
    private final AuthServiceProperties properties = new AuthServiceProperties();
    private final InMemoryOAuth2AuthorizationService authorizations = new InMemoryOAuth2AuthorizationService();
    private InMemoryUserStore users;
    private BrowserOAuthTokenResolver resolver;
    private OAuth2Authorization authorization;
    private String token;

    @BeforeEach
    void issueBrowserToken() {
        properties.getOauth().setIssuer(URI.create("http://localhost:8080/auth-service"));
        properties.setUsers(List.of(user(SUBJECT, ORIGINAL_USERNAME), user(OTHER, "other")));
        users = new InMemoryUserStore(properties);
        var client = new BrowserOAuthClients(properties).requireDeviceClient(PocketHiveDeviceAuth.HIVE_CLIENT_ID);
        var pending = OAuth2Authorization.withRegisteredClient(client)
            .principalName(SUBJECT.toString()).authorizationGrantType(AuthorizationGrantType.DEVICE_CODE)
            .authorizedScopes(Set.of(PocketHiveBrowserAuth.SCOPE)).build();
        OAuth2TokenContext context = mock(OAuth2TokenContext.class);
        when(context.getTokenType()).thenReturn(OAuth2TokenType.ACCESS_TOKEN);
        when(context.getAuthorization()).thenReturn(pending);
        when(context.getRegisteredClient()).thenReturn(client);
        when(context.getAuthorizedScopes()).thenReturn(pending.getAuthorizedScopes());
        var generated = (PocketHiveAccessToken) new PocketHiveAccessTokenGenerator(properties, users).generate(context);
        var accessToken = new OAuth2AccessToken(generated.getTokenType(), generated.getTokenValue(),
            generated.getIssuedAt(), generated.getExpiresAt(), generated.getScopes());
        authorization = OAuth2Authorization.from(pending)
            .token(accessToken, metadata -> metadata.put(
                OAuth2Authorization.Token.CLAIMS_METADATA_NAME, generated.getClaims())).build();
        token = generated.getTokenValue();
        authorizations.save(authorization);
        resolver = new BrowserOAuthTokenResolver(authorizations,
            new InMemoryRegisteredClientRepository(client), users, properties);
    }

    @Test
    void renamedSubjectKeepsCurrentIdentityAndGrantsWhenAnotherUserTakesItsUsername() {
        reassignUsername(true);
        var currentGrants = List.of(grant(PocketHivePermissionIds.RUN));
        users.replaceGrants(SUBJECT, currentGrants);

        var resolved = resolver.resolve(token);

        assertThat(resolved.id()).isEqualTo(SUBJECT);
        assertThat(resolved.username()).isEqualTo("renamed-viewer");
        assertThat(resolved.grants()).isEqualTo(currentGrants);
    }

    @Test
    void disabledSubjectCannotBeReplacedByTheActiveOwnerOfItsOldUsername() {
        reassignUsername(false);

        assertUnauthorized();
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "not-a-uuid", "00000000-0000-0000-0000-000000000099"})
    void missingMalformedOrUnknownSubjectCannotUseTheMatchingUsername(String subject) {
        var claims = new LinkedHashMap<>(authorization.getAccessToken().getClaims());
        claims.remove(JwtClaimNames.SUB);
        if (subject != null) claims.put(JwtClaimNames.SUB, subject);
        replaceClaims(claims);

        assertUnauthorized();
    }

    @Test
    void subjectMustBeAString() {
        var claims = new LinkedHashMap<>(authorization.getAccessToken().getClaims());
        claims.put(JwtClaimNames.SUB, SUBJECT);
        replaceClaims(claims);

        assertUnauthorized();
    }

    @Test
    void revokedTokenCannotRestoreTheSubject() {
        authorizations.save(OAuth2Authorization.from(authorization)
            .token(authorization.getAccessToken().getToken(),
                metadata -> metadata.put(OAuth2Authorization.Token.INVALIDATED_METADATA_NAME, true))
            .build());

        assertUnauthorized();
    }

    private void reassignUsername(boolean subjectActive) {
        users.upsertUser(SUBJECT, new UserUpsertRequestDto("renamed-viewer", "Renamed Viewer", subjectActive));
        users.upsertUser(OTHER, new UserUpsertRequestDto(ORIGINAL_USERNAME, "Other User", true));
        users.replaceGrants(OTHER, List.of(grant(PocketHivePermissionIds.ALL)));
    }

    private void replaceClaims(Map<String, Object> claims) {
        authorizations.save(OAuth2Authorization.from(authorization)
            .token(authorization.getAccessToken().getToken(),
                metadata -> metadata.put(OAuth2Authorization.Token.CLAIMS_METADATA_NAME, claims))
            .build());
    }

    private void assertUnauthorized() {
        assertThatThrownBy(() -> resolver.resolve(token))
            .isInstanceOfSatisfying(ResponseStatusException.class,
                error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED));
    }

    private static AuthServiceUserProperties user(UUID id, String username) {
        var user = new AuthServiceUserProperties();
        user.setId(id);
        user.setUsername(username);
        user.setDisplayName(username);
        user.setGrants(List.of(grant(PocketHivePermissionIds.VIEW)));
        return user;
    }

    private static AuthGrantDto grant(String permission) {
        return new AuthGrantDto(AuthProduct.POCKETHIVE, permission, PocketHiveResourceTypes.DEPLOYMENT, "*");
    }
}
