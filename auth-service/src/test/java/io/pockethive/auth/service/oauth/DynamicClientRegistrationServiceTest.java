package io.pockethive.auth.service.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.pockethive.auth.service.config.AuthServiceOAuthProperties;

import io.pockethive.auth.contract.PocketHiveMcpScopes;
import io.pockethive.auth.service.config.AuthServiceProperties;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;

class DynamicClientRegistrationServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-24T12:00:00Z");
    private static final Duration TTL = Duration.ofDays(30);
    private static final TokenSettings TOKENS = TokenSettings.builder()
        .refreshTokenTimeToLive(TTL)
        .build();

    @Test
    void registersPortablePublicClientsWithCanonicalMetadataAndEntropy() {
        MutableClock clock = new MutableClock(NOW);
        PocketHiveRegisteredClientRepository clients = repository(clock, 4);
        DynamicClientRegistrationService service = new DynamicClientRegistrationService(clients, TOKENS, clock);
        DynamicClientRegistrationRequest request = request(
            "  Portable Client  ",
            List.of("http://localhost:34123/callback?source=mcp", "https://client.example/callback"),
            List.of("refresh_token", "authorization_code"),
            List.of("code"),
            "none",
            PocketHiveMcpScopes.READ + " " + PocketHiveMcpScopes.DISCOVER);

        DynamicClientRegistrationResponse first = service.register(request);
        DynamicClientRegistrationResponse second = service.register(request);

        assertThat(first.clientId()).matches("phmcp_client_[A-Za-z0-9_-]{43}")
            .isNotEqualTo(second.clientId());
        assertThat(first.clientIdIssuedAt()).isEqualTo(NOW.getEpochSecond());
        assertThat(first.clientName()).isEqualTo("Portable Client");
        assertThat(first.redirectUris()).containsExactlyElementsOf(request.redirectUris());
        assertThat(first.grantTypes()).containsExactly("authorization_code", "refresh_token");
        assertThat(first.responseTypes()).containsExactly("code");
        assertThat(first.tokenEndpointAuthMethod()).isEqualTo("none");
        assertThat(first.scope()).isEqualTo(
            PocketHiveMcpScopes.DISCOVER + " " + PocketHiveMcpScopes.READ);

        RegisteredClient stored = clients.findByClientId(first.clientId());
        assertThat(stored.getClientName()).isEqualTo("Portable Client");
        assertThat(stored.getClientAuthenticationMethods()).containsExactly(ClientAuthenticationMethod.NONE);
        assertThat(stored.getAuthorizationGrantTypes()).containsExactlyInAnyOrder(
            AuthorizationGrantType.AUTHORIZATION_CODE, AuthorizationGrantType.REFRESH_TOKEN);
        assertThat(stored.getRedirectUris()).containsExactlyInAnyOrderElementsOf(request.redirectUris());
        assertThat(stored.getScopes()).containsExactlyInAnyOrder(
            PocketHiveMcpScopes.DISCOVER, PocketHiveMcpScopes.READ);
        assertThat(stored.getClientSettings().isRequireProofKey()).isTrue();
        assertThat(stored.getClientSettings().isRequireAuthorizationConsent()).isTrue();
        assertThat(stored.getTokenSettings()).isSameAs(TOKENS);
    }

    @Test
    void assignsCanonicalInteractiveScopesWhenRegistrationScopeIsOmitted() {
        PocketHiveRegisteredClientRepository clients = repository(new MutableClock(NOW), 1);
        DynamicClientRegistrationService service =
            new DynamicClientRegistrationService(clients, TOKENS, Clock.fixed(NOW, ZoneOffset.UTC));

        DynamicClientRegistrationResponse response = service.register(request(
            "kiro", List.of("http://localhost:38124/oauth/callback"), grants(), codes(), "none", null));

        assertThat(response.scope()).isEqualTo(String.join(" ", PocketHiveMcpScopes.COMPANION_ORDERED));
        assertThat(clients.findByClientId(response.clientId()).getScopes())
            .containsExactlyInAnyOrderElementsOf(PocketHiveMcpScopes.COMPANION_ORDERED)
            .doesNotContain(PocketHiveMcpScopes.CLEANUP);
    }

    @Test
    void substitutesPublicNoneWhenNativeClientAuthenticationMetadataIsOmitted() {
        PocketHiveRegisteredClientRepository clients = repository(new MutableClock(NOW), 1);
        DynamicClientRegistrationResponse response = new DynamicClientRegistrationService(
            clients, TOKENS, Clock.fixed(NOW, ZoneOffset.UTC)).register(request(
                "kiro", List.of("http://127.0.0.1:52000/oauth/callback"), grants(), codes(), null, null));

        assertThat(response.tokenEndpointAuthMethod()).isEqualTo("none");
        assertThat(clients.findByClientId(response.clientId()).getClientAuthenticationMethods())
            .containsExactly(ClientAuthenticationMethod.NONE);
    }

    @Test
    void acceptsTheDeclaredLoopbackAndHttpsRedirectFormsAndAuthorizationCodeOnly() {
        DynamicClientRegistrationService service = service(8);
        for (String redirect : List.of(
            "http://127.0.0.1:1/callback",
            "http://[::1]:65535/callback",
            "http://[0:0:0:0:0:0:0:1]/callback",
            "http://localhost/callback",
            "https://client.example/callback")) {
            DynamicClientRegistrationResponse registered = service.register(request(
                "Client", List.of(redirect), List.of("authorization_code"), List.of("code"), "none",
                PocketHiveMcpScopes.DISCOVER));
            assertThat(registered.redirectUris()).containsExactly(redirect);
            assertThat(registered.grantTypes()).containsExactly("authorization_code");
        }

        String maximumLengthRedirect = "https://client.example/"
            + "x".repeat(2048 - "https://client.example/".length());
        List<String> maximumRedirectCount = java.util.stream.IntStream.rangeClosed(1, 8)
            .mapToObj(index -> "https://client" + index + ".example/callback")
            .toList();
        assertThat(service.register(request(
            "x".repeat(128), List.of(maximumLengthRedirect), List.of("authorization_code"),
            List.of("code"), "none", PocketHiveMcpScopes.DISCOVER)).clientName()).hasSize(128);
        assertThat(service.register(request(
            "Client", maximumRedirectCount, List.of("authorization_code"), List.of("code"),
            "none", PocketHiveMcpScopes.DISCOVER)).redirectUris()).hasSize(8);
    }

    @Test
    void rejectsEveryUnsafeRegistrationMetadataBoundary() {
        String safe = "http://127.0.0.1:34123/callback";
        List<DynamicClientRegistrationRequest> invalidMetadata = List.of(
            request(null, List.of(safe), grants(), codes(), "none", PocketHiveMcpScopes.DISCOVER),
            request(" ", List.of(safe), grants(), codes(), "none", PocketHiveMcpScopes.DISCOVER),
            request("bad\nname", List.of(safe), grants(), codes(), "none", PocketHiveMcpScopes.DISCOVER),
            request("x".repeat(129), List.of(safe), grants(), codes(), "none", PocketHiveMcpScopes.DISCOVER),
            request("client", List.of(safe), List.of(), codes(), "none", PocketHiveMcpScopes.DISCOVER),
            request("client", List.of(safe), List.of("refresh_token"), codes(), "none",
                PocketHiveMcpScopes.DISCOVER),
            request("client", List.of(safe), List.of("authorization_code", "authorization_code"), codes(),
                "none", PocketHiveMcpScopes.DISCOVER),
            request("client", List.of(safe), List.of("authorization_code", "implicit"), codes(), "none",
                PocketHiveMcpScopes.DISCOVER),
            request("client", List.of(safe), grants(), List.of(), "none", PocketHiveMcpScopes.DISCOVER),
            request("client", List.of(safe), grants(), List.of("token"), "none",
                PocketHiveMcpScopes.DISCOVER),
            request("client", List.of(safe), grants(), codes(), "client_secret_basic",
                PocketHiveMcpScopes.DISCOVER),
            request("client", List.of(safe), grants(), codes(), "none", " "),
            request("client", List.of(safe), grants(), codes(), "none",
                PocketHiveMcpScopes.DISCOVER + " " + PocketHiveMcpScopes.DISCOVER),
            request("client", List.of(safe), grants(), codes(), "none", PocketHiveMcpScopes.CLEANUP),
            request("client", List.of(safe), grants(), codes(), "none", "unknown"));
        for (DynamicClientRegistrationRequest request : invalidMetadata) {
            assertRegistrationError(service(4), request, "invalid_client_metadata", HttpStatus.BAD_REQUEST);
        }
        assertRegistrationError(service(4), null, "invalid_client_metadata", HttpStatus.BAD_REQUEST);
    }

    @Test
    void rejectsEveryUnsafeRedirectBoundary() {
        String safe = "http://127.0.0.1:34123/callback";
        List<List<String>> invalidRedirects = new ArrayList<>();
        invalidRedirects.add(null);
        invalidRedirects.add(List.of());
        invalidRedirects.add(List.of(safe, safe));
        invalidRedirects.add(List.of("relative/callback"));
        invalidRedirects.add(List.of("http://remote.example/callback"));
        invalidRedirects.add(List.of("file:///tmp/callback"));
        invalidRedirects.add(List.of("ftp://client.example/callback"));
        invalidRedirects.add(List.of("https://user@client.example/callback"));
        invalidRedirects.add(List.of("https://client.example/callback#fragment"));
        invalidRedirects.add(List.of("https://"));
        invalidRedirects.add(List.of("https://client.example/" + "x".repeat(2048)));
        invalidRedirects.add(List.of(
            "https://one.example", "https://two.example", "https://three.example",
            "https://four.example", "https://five.example", "https://six.example",
            "https://seven.example", "https://eight.example", "https://nine.example"));
        List<String> nullRedirect = new ArrayList<>();
        nullRedirect.add(null);
        invalidRedirects.add(nullRedirect);
        for (List<String> redirects : invalidRedirects) {
            assertRegistrationError(service(4), request(
                "client", redirects, grants(), codes(), "none", PocketHiveMcpScopes.DISCOVER),
                "invalid_redirect_uri", HttpStatus.BAD_REQUEST);
        }
    }

    @Test
    void registryKeepsFixedClientsBoundsDynamicClientsAndPrunesAtExpiry() {
        MutableClock clock = new MutableClock(NOW);
        PocketHiveRegisteredClientRepository clients = repository(clock, 1);
        RegisteredClient fixed = clients.findByClientId("fixed-client");
        assertThat(fixed).isSameAs(clients.findById("fixed"));

        RegisteredClient first = dynamic("one", "client-one");
        clients.save(first);
        assertThat(clients.findById("one")).isSameAs(first);
        assertThat(clients.findByClientId("client-one")).isSameAs(first);
        assertThatThrownBy(() -> clients.save(dynamic("two", "client-two")))
            .isInstanceOf(DynamicClientCapacityException.class);
        assertThatThrownBy(() -> clients.save(dynamic("fixed", "other")))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("OAUTH_FIXED_CLIENT_IMMUTABLE");
        assertThatThrownBy(() -> clients.save(dynamic("other", "fixed-client")))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("OAUTH_FIXED_CLIENT_IMMUTABLE");

        clock.current = NOW.plus(TTL);
        assertThat(clients.findById("one")).isNull();
        assertThat(clients.findByClientId("client-one")).isNull();
        clients.save(dynamic("two", "client-two"));
        assertThat(clients.findByClientId("client-two")).isNotNull();
    }

    @Test
    void registryPrunesBeforeSaveAndOnEachDynamicLookupPath() {
        MutableClock saveClock = new MutableClock(NOW);
        PocketHiveRegisteredClientRepository saveClients = repository(saveClock, 1);
        saveClients.save(dynamic("one", "client-one"));
        saveClock.current = NOW.plus(TTL);
        saveClients.save(dynamic("two", "client-two"));
        assertThat(saveClients.findByClientId("client-two")).isNotNull();

        MutableClock clientIdClock = new MutableClock(NOW);
        PocketHiveRegisteredClientRepository clientIdClients = repository(clientIdClock, 1);
        clientIdClients.save(dynamic("one", "client-one"));
        clientIdClock.current = NOW.plus(TTL);
        assertThat(clientIdClients.findByClientId("client-one")).isNull();
    }

    @Test
    void successfulDynamicClientLookupsRenewTheInactivityDeadline() {
        MutableClock clock = new MutableClock(NOW);
        PocketHiveRegisteredClientRepository clients = repository(clock, 1);
        RegisteredClient active = dynamic("active", "active-client");
        clients.save(active);

        clock.current = NOW.plus(TTL).minusSeconds(1);
        assertThat(clients.findById("active")).isSameAs(active);
        clock.current = NOW.plus(TTL).plusSeconds(1);
        assertThat(clients.findByClientId("active-client")).isSameAs(active);

        clock.current = NOW.plus(TTL.multipliedBy(2)).plusSeconds(1);
        assertThat(clients.findById("active")).isNull();
    }

    @Test
    void requiresDynamicClientInactivityLifetimeToExceedRefreshLifetime() {
        AuthServiceProperties properties = validOAuthProperties();
        AuthServiceOAuthProperties oauth = properties.getOauth();
        oauth.setRefreshTokenTtl(TTL);
        oauth.setDynamicClientTtl(TTL);

        assertThatThrownBy(() -> PocketHiveOAuthConfiguration.requireValid(properties))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("POCKETHIVE_OAUTH_CONFIGURATION_INVALID");

        oauth.setDynamicClientTtl(TTL.plusSeconds(1));
        assertThat(PocketHiveOAuthConfiguration.requireValid(properties)).isSameAs(oauth);
    }

    @Test
    void requiresCanonicalPortlessIpLoopbackForVscodeRedirect() {
        AuthServiceProperties properties = validOAuthProperties();
        AuthServiceOAuthProperties oauth = properties.getOauth();

        for (String redirect : List.of(
            "http://127.0.0.1:52000/callback",
            "http://localhost/callback",
            "https://127.0.0.1/callback",
            "http://127.0.0.1/other",
            "http://user@127.0.0.1/callback",
            "http://127.0.0.1/callback?query=value",
            "http://127.0.0.1/callback#fragment"
        )) {
            oauth.setVscodeRedirectUri(URI.create(redirect));
            assertThatThrownBy(() -> PocketHiveOAuthConfiguration.requireValid(properties))
                .as(redirect)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("POCKETHIVE_OAUTH_CONFIGURATION_INVALID");
        }

        oauth.setVscodeRedirectUri(URI.create("http://127.0.0.1/callback"));
        assertThat(PocketHiveOAuthConfiguration.requireValid(properties)).isSameAs(oauth);
    }

    @Test
    void defaultsDynamicClientInactivityLifetimeBeyondRefreshLifetime() {
        AuthServiceOAuthProperties oauth = new AuthServiceOAuthProperties();

        assertThat(oauth.getRefreshTokenTtl()).isEqualTo(Duration.ofDays(30));
        assertThat(oauth.getDynamicClientTtl()).isEqualTo(Duration.ofDays(31));
    }

    @Test
    void registryRejectsEveryDynamicIdentifierCollision() {
        PocketHiveRegisteredClientRepository clients = repository(new MutableClock(NOW), 3);
        clients.save(dynamic("one", "client-one"));

        assertThatThrownBy(() -> clients.save(dynamic("one", "client-two")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("OAUTH_DYNAMIC_CLIENT_IDENTIFIER_DUPLICATE");
        assertThatThrownBy(() -> clients.save(dynamic("two", "client-one")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("OAUTH_DYNAMIC_CLIENT_IDENTIFIER_DUPLICATE");
    }

    @Test
    void clientIdentifierAllocationIsBoundedWhenEntropyCollides() {
        MutableClock clock = new MutableClock(NOW);
        PocketHiveRegisteredClientRepository clients = repository(clock, 2);
        String zeroEntropyClientId = "phmcp_client_"
            + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]);
        clients.save(dynamic("collision", zeroEntropyClientId));
        CountingZeroSecureRandom random = new CountingZeroSecureRandom();
        DynamicClientRegistrationService service =
            new DynamicClientRegistrationService(clients, TOKENS, clock, random);

        assertRegistrationError(service, validRequest(), "temporarily_unavailable",
            HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(random.calls).isEqualTo(8);
    }

    @Test
    void serviceReturnsBoundedStandardErrorWhenRegistryIsFull() {
        DynamicClientRegistrationService service = service(1);
        service.register(validRequest());
        assertRegistrationError(service, validRequest(), "temporarily_unavailable",
            HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    void rejectsInvalidRegistryConstructionAndIdentifiers() {
        MutableClock clock = new MutableClock(NOW);
        RegisteredClient fixed = fixed();
        for (Runnable construction : List.<Runnable>of(
            () -> new PocketHiveRegisteredClientRepository(null, 1, TTL, clock),
            () -> new PocketHiveRegisteredClientRepository(List.of(), 1, TTL, clock),
            () -> new PocketHiveRegisteredClientRepository(List.of(fixed), 0, TTL, clock),
            () -> new PocketHiveRegisteredClientRepository(List.of(fixed), 1, Duration.ZERO, clock),
            () -> new PocketHiveRegisteredClientRepository(List.of(fixed), 1, Duration.ofSeconds(-1), clock),
            () -> new PocketHiveRegisteredClientRepository(List.of(fixed), 1, TTL, null))) {
            assertThatThrownBy(construction::run).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("OAUTH_CLIENT_REGISTRY_CONFIGURATION_INVALID");
        }
        for (List<RegisteredClient> duplicate : List.of(
            List.of(fixed, dynamic("fixed", "other-client")),
            List.of(fixed, dynamic("other", "fixed-client")))) {
            assertThatThrownBy(() -> new PocketHiveRegisteredClientRepository(
                duplicate, 1, TTL, clock))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("OAUTH_FIXED_CLIENT_IDENTIFIER_DUPLICATE");
        }
        PocketHiveRegisteredClientRepository clients = repository(clock, 1);
        assertThatThrownBy(() -> clients.findById(" ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> clients.findByClientId(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> clients.save(null)).isInstanceOf(NullPointerException.class);
    }

    private static DynamicClientRegistrationService service(int capacity) {
        MutableClock clock = new MutableClock(NOW);
        return new DynamicClientRegistrationService(repository(clock, capacity), TOKENS, clock);
    }

    private static PocketHiveRegisteredClientRepository repository(Clock clock, int capacity) {
        return new PocketHiveRegisteredClientRepository(List.of(fixed()), capacity, TTL, clock);
    }

    private static RegisteredClient fixed() {
        return RegisteredClient.withId("fixed")
            .clientId("fixed-client")
            .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
            .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
            .redirectUri("http://127.0.0.1/callback")
            .build();
    }

    private static RegisteredClient dynamic(String id, String clientId) {
        return RegisteredClient.withId(id)
            .clientId(clientId)
            .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
            .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
            .redirectUri("http://127.0.0.1/callback")
            .build();
    }

    private static AuthServiceProperties validOAuthProperties() {
        AuthServiceProperties properties = new AuthServiceProperties();
        AuthServiceOAuthProperties oauth = properties.getOauth();
        oauth.setIssuer(URI.create("http://127.0.0.1:8088/auth-service"));
        oauth.setResource(URI.create("http://127.0.0.1:8088/mcp"));
        oauth.setVscodeClientId("vscode-client");
        oauth.setVscodeRedirectUri(URI.create("http://127.0.0.1/callback"));
        oauth.setIntrospectionClientId("introspection-client");
        oauth.setIntrospectionClientSecret("introspection-secret");
        return properties;
    }

    private static DynamicClientRegistrationRequest validRequest() {
        return request("client", List.of("http://127.0.0.1:34123/callback"), grants(), codes(), "none",
            PocketHiveMcpScopes.DISCOVER);
    }

    private static List<String> grants() {
        return List.of("authorization_code", "refresh_token");
    }

    private static List<String> codes() {
        return List.of("code");
    }

    private static DynamicClientRegistrationRequest request(
        String name, List<String> redirects, List<String> grantTypes, List<String> responseTypes,
        String authenticationMethod, String scopes
    ) {
        return new DynamicClientRegistrationRequest(
            name, redirects, grantTypes, responseTypes, authenticationMethod, scopes);
    }

    private static void assertRegistrationError(DynamicClientRegistrationService service,
                                                DynamicClientRegistrationRequest request,
                                                String error, HttpStatus status) {
        assertThatThrownBy(() -> service.register(request))
            .isInstanceOfSatisfying(DynamicClientRegistrationException.class, failure -> {
                assertThat(failure.error()).isEqualTo(error);
                assertThat(failure.status()).isEqualTo(status);
                assertThat(failure.getMessage()).isNotBlank();
            });
    }

    private static final class MutableClock extends Clock {
        private Instant current;

        private MutableClock(Instant current) {
            this.current = current;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return current;
        }
    }

    private static final class CountingZeroSecureRandom extends java.security.SecureRandom {
        private int calls;

        @Override
        public void nextBytes(byte[] bytes) {
            calls++;
            Arrays.fill(bytes, (byte) 0);
        }
    }
}
