package io.pockethive.auth.service.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.pockethive.auth.contract.AuthGrantDto;
import io.pockethive.auth.contract.AuthProduct;
import io.pockethive.auth.contract.PocketHiveMcpScopes;
import io.pockethive.auth.contract.PocketHivePermissionIds;
import io.pockethive.auth.service.config.AuthServiceProperties;
import io.pockethive.auth.service.service.InMemoryUserStore;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.context.AuthorizationServerContext;
import org.springframework.security.oauth2.server.authorization.context.AuthorizationServerContextHolder;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenContext;

class PocketHivePublicSessionSecurityTest {
    private static final String CLIENT_ID = "pockethive-vscode";
    private static final Duration REFRESH_TTL = Duration.ofHours(8);
    private static final AuthorizationServerSettings SETTINGS = AuthorizationServerSettings.builder()
        .issuer("http://localhost:8080/auth-service")
        .tokenEndpoint("/oauth/token")
        .tokenRevocationEndpoint("/oauth/revoke")
        .build();

    @Test
    void refreshGeneratorIssuesExactEntropyAndTtlForEveryDeclaredCompanionScopeProfile() {
        PocketHiveRefreshTokenGenerator generator = new PocketHiveRefreshTokenGenerator();
        for (Set<String> scopes : List.of(
                Set.of(PocketHiveMcpScopes.DISCOVER, PocketHiveMcpScopes.READ),
                Set.of(PocketHiveMcpScopes.DISCOVER, PocketHiveMcpScopes.READ,
                    PocketHiveMcpScopes.OPERATE, PocketHiveMcpScopes.AUTHOR),
                Set.copyOf(PocketHiveMcpScopes.COMPANION_ORDERED))) {
            OAuth2TokenContext context = tokenContext(OAuth2TokenType.REFRESH_TOKEN, scopes);
            OAuth2RefreshToken first = generator.generate(context);
            OAuth2RefreshToken second = generator.generate(context);

            assertThat(first).isNotNull();
            assertThat(first.getTokenValue()).startsWith("phrfr_").hasSize(92);
            assertThat(first.getTokenValue()).matches("phrfr_[A-Za-z0-9_-]{86}");
            assertThat(second).isNotNull();
            assertThat(second.getTokenValue()).isNotEqualTo(first.getTokenValue());
            assertThat(Duration.between(first.getIssuedAt(), first.getExpiresAt())).isEqualTo(REFRESH_TTL);
        }
    }

    @Test
    void refreshGeneratorRejectsEveryNonBaseContext() {
        PocketHiveRefreshTokenGenerator generator = new PocketHiveRefreshTokenGenerator();
        assertThat(generator.generate(tokenContext(
            OAuth2TokenType.ACCESS_TOKEN,
            Set.of(PocketHiveMcpScopes.DISCOVER, PocketHiveMcpScopes.READ)))).isNull();
        assertThat(generator.generate(tokenContext(
            OAuth2TokenType.REFRESH_TOKEN,
            Set.of(PocketHiveMcpScopes.DISCOVER)))).isNull();
        assertThat(generator.generate(tokenContext(
            OAuth2TokenType.REFRESH_TOKEN,
            Set.of(PocketHiveMcpScopes.DISCOVER, PocketHiveMcpScopes.READ, PocketHiveMcpScopes.OPERATE)))).isNull();
    }

    @Test
    void accessTokensRequireTheCompleteOriginalConsentFromCurrentGrants() {
        AuthServiceProperties properties = userProperties("viewer", true, PocketHivePermissionIds.VIEW);
        InMemoryUserStore currentUsers = new InMemoryUserStore(properties);
        PocketHiveAccessTokenGenerator generator = new PocketHiveAccessTokenGenerator(properties, currentUsers);
        OAuth2TokenContext context = accessTokenContext("viewer", Set.of(
            PocketHiveMcpScopes.DISCOVER, PocketHiveMcpScopes.READ,
            PocketHiveMcpScopes.OPERATE, PocketHiveMcpScopes.AUTHOR));

        assertOAuthError(() -> generator.generate(context), OAuth2ErrorCodes.INVALID_GRANT);
        PocketHiveAccessToken viewer = (PocketHiveAccessToken) generator.generate(accessTokenContext(
            "viewer", Set.of(PocketHiveMcpScopes.DISCOVER, PocketHiveMcpScopes.READ)));
        PocketHiveAccessToken secondViewer = (PocketHiveAccessToken) generator.generate(accessTokenContext(
            "viewer", Set.of(PocketHiveMcpScopes.DISCOVER, PocketHiveMcpScopes.READ)));
        assertThat(viewer.getScopes())
            .containsExactlyInAnyOrder(PocketHiveMcpScopes.DISCOVER, PocketHiveMcpScopes.READ);
        assertThat(viewer.getTokenValue()).matches("phmcp_[A-Za-z0-9_-]{43}")
            .isNotEqualTo(secondViewer.getTokenValue());
        assertThat(Set.copyOf(List.of(((String) viewer.getClaims().get("scope")).split(" "))))
            .containsExactlyInAnyOrder(PocketHiveMcpScopes.DISCOVER, PocketHiveMcpScopes.READ);

        currentUsers.replaceGrants(UUID.fromString("00000000-0000-0000-0000-000000000001"), List.of());
        assertOAuthError(() -> generator.generate(accessTokenContext("viewer", Set.of(
            PocketHiveMcpScopes.DISCOVER, PocketHiveMcpScopes.READ))), OAuth2ErrorCodes.INVALID_GRANT);

        AuthServiceProperties inactiveProperties = userProperties("inactive", false, PocketHivePermissionIds.VIEW);
        PocketHiveAccessTokenGenerator inactive = new PocketHiveAccessTokenGenerator(
            inactiveProperties, new InMemoryUserStore(inactiveProperties));
        assertOAuthError(() -> inactive.generate(accessTokenContext("inactive", Set.of(
            PocketHiveMcpScopes.DISCOVER, PocketHiveMcpScopes.READ))), OAuth2ErrorCodes.INVALID_GRANT);

        assertThat(generator.generate(tokenContext(OAuth2TokenType.REFRESH_TOKEN,
            Set.of(PocketHiveMcpScopes.DISCOVER, PocketHiveMcpScopes.READ)))).isNull();
    }

    @Test
    void companionConverterNarrowsOnlyTheExactAuthenticatedCompanionIntent() {
        PocketHiveCompanionAuthorizationRequestConverter converter =
            new PocketHiveCompanionAuthorizationRequestConverter(CLIENT_ID, users("viewer", true));
        Authentication viewer = UsernamePasswordAuthenticationToken.authenticated("viewer", "", List.of());

        assertThat(convert(converter, CLIENT_ID, PocketHiveMcpScopes.COMPANION_ORDERED, viewer).getScopes())
            .containsExactlyInAnyOrder(PocketHiveMcpScopes.DISCOVER, PocketHiveMcpScopes.READ);
        assertThat(convert(converter, "other-client", PocketHiveMcpScopes.COMPANION_ORDERED, viewer).getScopes())
            .containsExactlyInAnyOrderElementsOf(PocketHiveMcpScopes.COMPANION);
        assertThat(convert(converter, CLIENT_ID,
            List.of(PocketHiveMcpScopes.DISCOVER, PocketHiveMcpScopes.READ), viewer).getScopes())
            .containsExactlyInAnyOrder(PocketHiveMcpScopes.DISCOVER, PocketHiveMcpScopes.READ);
        assertThat(convert(converter, CLIENT_ID, PocketHiveMcpScopes.COMPANION_ORDERED, null).getScopes())
            .containsExactlyInAnyOrderElementsOf(PocketHiveMcpScopes.COMPANION);
    }

    @Test
    void companionConverterKeepsUnknownOrInactivePrincipalsForCanonicalValidationAndRejectsInvalidConstruction() {
        Authentication unknown = UsernamePasswordAuthenticationToken.authenticated("unknown", "", List.of());
        PocketHiveCompanionAuthorizationRequestConverter active =
            new PocketHiveCompanionAuthorizationRequestConverter(CLIENT_ID, users("viewer", true));
        PocketHiveCompanionAuthorizationRequestConverter inactive =
            new PocketHiveCompanionAuthorizationRequestConverter(CLIENT_ID, users("viewer", false));

        assertThat(convert(active, CLIENT_ID, PocketHiveMcpScopes.COMPANION_ORDERED, unknown).getScopes())
            .containsExactlyInAnyOrderElementsOf(PocketHiveMcpScopes.COMPANION);
        assertThat(convert(inactive, CLIENT_ID, PocketHiveMcpScopes.COMPANION_ORDERED,
            UsernamePasswordAuthenticationToken.authenticated("viewer", "", List.of())).getScopes())
            .containsExactlyInAnyOrderElementsOf(PocketHiveMcpScopes.COMPANION);
        assertThatThrownBy(() -> new PocketHiveCompanionAuthorizationRequestConverter(" ", users("viewer", true)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("OAUTH_COMPANION_CLIENT_ID_REQUIRED");
        assertThatThrownBy(() -> new PocketHiveCompanionAuthorizationRequestConverter(CLIENT_ID, null))
            .isInstanceOf(NullPointerException.class).hasMessage("users");
    }

    @Test
    void converterRecognizesOnlyExactPublicRefreshAndRevocationRequests() {
        PocketHivePublicSessionClientAuthenticationConverter converter =
            new PocketHivePublicSessionClientAuthenticationConverter(SETTINGS);

        assertThat(converter.convert(request("GET", "/oauth/token", "refresh_token"))).isNull();
        MockHttpServletRequest authorized = request("POST", "/oauth/token", "refresh_token");
        authorized.addHeader("Authorization", "Basic forbidden");
        assertThat(converter.convert(authorized)).isNull();
        assertThat(converter.convert(request("POST", "/oauth/token", "authorization_code"))).isNull();
        assertThat(converter.convert(request("POST", "/oauth/other", "refresh_token"))).isNull();

        OAuth2ClientAuthenticationToken refresh = (OAuth2ClientAuthenticationToken) converter.convert(
            request("POST", "/oauth/token", "refresh_token"));
        assertPublicCandidate(refresh);
        OAuth2ClientAuthenticationToken revocation = (OAuth2ClientAuthenticationToken) converter.convert(
            request("POST", "/oauth/revoke", null));
        assertPublicCandidate(revocation);

        MockHttpServletRequest ingressRefresh = request("POST", "/auth-service/oauth/token", "refresh_token");
        ingressRefresh.setContextPath("/auth-service");
        assertPublicCandidate((OAuth2ClientAuthenticationToken) converter.convert(ingressRefresh));

        MockHttpServletRequest inconsistentPath = request("POST", "/oauth/token", "refresh_token");
        inconsistentPath.setContextPath("/auth-service");
        assertThat(converter.convert(inconsistentPath)).isNull();
    }

    @Test
    void converterRejectsMalformedPublicClientCredentialsWithExactErrors() {
        PocketHivePublicSessionClientAuthenticationConverter converter =
            new PocketHivePublicSessionClientAuthenticationConverter(SETTINGS);
        MockHttpServletRequest missing = request("POST", "/oauth/token", "refresh_token");
        missing.removeParameter("client_id");
        assertOAuthError(() -> converter.convert(missing), OAuth2ErrorCodes.INVALID_REQUEST);

        MockHttpServletRequest blank = request("POST", "/oauth/token", "refresh_token");
        blank.setParameter("client_id", "   ");
        assertOAuthError(() -> converter.convert(blank), OAuth2ErrorCodes.INVALID_REQUEST);

        MockHttpServletRequest duplicate = request("POST", "/oauth/token", "refresh_token");
        duplicate.setParameter("client_id", CLIENT_ID, CLIENT_ID);
        assertOAuthError(() -> converter.convert(duplicate), OAuth2ErrorCodes.INVALID_REQUEST);

        MockHttpServletRequest secret = request("POST", "/oauth/revoke", null);
        secret.setParameter("client_secret", "forbidden");
        assertOAuthError(() -> converter.convert(secret), OAuth2ErrorCodes.INVALID_CLIENT);
    }

    @Test
    void providerAuthenticatesOnlyMarkedRegisteredRefreshCapablePublicClient() {
        RegisteredClient valid = publicClient();
        RegisteredClientRepository repository = mock(RegisteredClientRepository.class);
        when(repository.findByClientId(CLIENT_ID)).thenReturn(valid);
        PocketHivePublicSessionClientAuthenticationProvider provider =
            new PocketHivePublicSessionClientAuthenticationProvider(repository);

        assertThat(provider.authenticate(candidate(Map.of()))).isNull();
        assertThat(provider.authenticate(candidate(Map.of(
            PocketHivePublicSessionClientAuthenticationConverter.SESSION_CLIENT_MARKER, Boolean.FALSE)))).isNull();

        Authentication result = provider.authenticate(candidate(Map.of(
            PocketHivePublicSessionClientAuthenticationConverter.SESSION_CLIENT_MARKER, Boolean.TRUE)));
        assertThat(result).isInstanceOf(OAuth2ClientAuthenticationToken.class);
        OAuth2ClientAuthenticationToken authenticated = (OAuth2ClientAuthenticationToken) result;
        assertThat(authenticated.getRegisteredClient()).isSameAs(valid);
        assertThat(authenticated.getClientAuthenticationMethod()).isEqualTo(ClientAuthenticationMethod.NONE);
        assertThat(provider.supports(OAuth2ClientAuthenticationToken.class)).isTrue();
        assertThat(provider.supports(Authentication.class)).isFalse();
    }

    @Test
    void providerRejectsUnknownConfidentialAndNonRefreshClients() {
        for (RegisteredClient registered : new RegisteredClient[] {
            null,
            RegisteredClient.withId("confidential")
                .clientId(CLIENT_ID)
                .clientSecret("secret")
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
                .build(),
            RegisteredClient.withId("no-refresh")
                .clientId(CLIENT_ID)
                .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("http://127.0.0.1/callback")
                .build(),
        }) {
            RegisteredClientRepository repository = mock(RegisteredClientRepository.class);
            when(repository.findByClientId(CLIENT_ID)).thenReturn(registered);
            PocketHivePublicSessionClientAuthenticationProvider provider =
                new PocketHivePublicSessionClientAuthenticationProvider(repository);
            assertOAuthError(() -> provider.authenticate(candidate(Map.of(
                PocketHivePublicSessionClientAuthenticationConverter.SESSION_CLIENT_MARKER, Boolean.TRUE))),
                OAuth2ErrorCodes.INVALID_CLIENT);
        }
    }

    private static OAuth2TokenContext tokenContext(OAuth2TokenType type, Set<String> scopes) {
        OAuth2TokenContext context = mock(OAuth2TokenContext.class);
        when(context.getTokenType()).thenReturn(type);
        when(context.getAuthorizedScopes()).thenReturn(scopes);
        when(context.getRegisteredClient()).thenReturn(publicClient());
        return context;
    }

    private static OAuth2TokenContext accessTokenContext(String username, Set<String> scopes) {
        OAuth2TokenContext context = tokenContext(OAuth2TokenType.ACCESS_TOKEN, scopes);
        OAuth2Authorization authorization = mock(OAuth2Authorization.class);
        when(authorization.getPrincipalName()).thenReturn(username);
        when(context.getAuthorization()).thenReturn(authorization);
        return context;
    }

    private static OAuth2AuthorizationCodeRequestAuthenticationToken convert(
        PocketHiveCompanionAuthorizationRequestConverter converter,
        String clientId,
        List<String> scopes,
        Authentication principal
    ) {
        AuthorizationServerContext context = mock(AuthorizationServerContext.class);
        when(context.getIssuer()).thenReturn(SETTINGS.getIssuer());
        when(context.getAuthorizationServerSettings()).thenReturn(SETTINGS);
        AuthorizationServerContextHolder.setContext(context);
        if (principal == null) {
            SecurityContextHolder.clearContext();
        } else {
            SecurityContextHolder.getContext().setAuthentication(principal);
        }
        try {
            MockHttpServletRequest request = new MockHttpServletRequest("POST", "/oauth/authorize");
            request.setContentType("application/x-www-form-urlencoded");
            request.setParameter("response_type", "code");
            request.setParameter("client_id", clientId);
            request.setParameter("redirect_uri", "http://127.0.0.1/callback");
            request.setParameter("scope", String.join(" ", scopes));
            request.setParameter("state", "state");
            request.setParameter("code_challenge", "challenge");
            request.setParameter("code_challenge_method", "S256");
            return (OAuth2AuthorizationCodeRequestAuthenticationToken) converter.convert(request);
        } finally {
            SecurityContextHolder.clearContext();
            AuthorizationServerContextHolder.resetContext();
        }
    }

    private static InMemoryUserStore users(String username, boolean active) {
        return new InMemoryUserStore(userProperties(username, active, PocketHivePermissionIds.VIEW));
    }

    private static AuthServiceProperties userProperties(String username, boolean active, String permission) {
        AuthServiceProperties properties = new AuthServiceProperties();
        properties.getOauth().setIssuer(URI.create("http://localhost:8080/auth-service"));
        properties.getOauth().setResource(URI.create("http://localhost:8080/mcp"));
        AuthServiceProperties.UserConfig user = new AuthServiceProperties.UserConfig();
        user.setId(UUID.fromString("00000000-0000-0000-0000-000000000001"));
        user.setUsername(username);
        user.setDisplayName(username);
        user.setActive(active);
        user.setGrants(List.of(new AuthGrantDto(
            AuthProduct.POCKETHIVE, permission, "PH_DEPLOYMENT", "*")));
        properties.setUsers(List.of(user));
        return properties;
    }

    private static RegisteredClient publicClient() {
        return RegisteredClient.withId("public")
            .clientId(CLIENT_ID)
            .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
            .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
            .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
            .redirectUri("http://127.0.0.1/callback")
            .tokenSettings(TokenSettings.builder().refreshTokenTimeToLive(REFRESH_TTL).build())
            .build();
    }

    private static MockHttpServletRequest request(String method, String path, String grantType) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setParameter("client_id", CLIENT_ID);
        if (grantType != null) request.setParameter("grant_type", grantType);
        return request;
    }

    private static OAuth2ClientAuthenticationToken candidate(Map<String, Object> additional) {
        return new OAuth2ClientAuthenticationToken(
            CLIENT_ID, ClientAuthenticationMethod.NONE, null, additional);
    }

    private static void assertPublicCandidate(OAuth2ClientAuthenticationToken candidate) {
        assertThat(candidate).isNotNull();
        assertThat(candidate.getPrincipal()).isEqualTo(CLIENT_ID);
        assertThat(candidate.getClientAuthenticationMethod()).isEqualTo(ClientAuthenticationMethod.NONE);
        assertThat(candidate.getAdditionalParameters()).containsEntry(
            PocketHivePublicSessionClientAuthenticationConverter.SESSION_CLIENT_MARKER, Boolean.TRUE);
    }

    private static void assertOAuthError(Runnable action, String code) {
        assertThatThrownBy(action::run)
            .isInstanceOfSatisfying(OAuth2AuthenticationException.class,
                error -> assertThat(error.getError().getErrorCode()).isEqualTo(code));
    }
}
