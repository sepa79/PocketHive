package io.pockethive.auth.service.oauth;

import static io.pockethive.auth.service.oauth.PocketHivePublicSessionClientAuthenticationConverter.REFRESH_REQUEST;
import static io.pockethive.auth.service.oauth.PocketHivePublicSessionClientAuthenticationConverter.SESSION_CLIENT_MARKER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.pockethive.auth.service.config.AuthServiceProperties;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.client.InMemoryRegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;

class PocketHivePublicSessionClientAuthenticationProviderTest {
    @ParameterizedTest
    @MethodSource("browserClients")
    void acceptsBrowserRevocationButRejectsRefreshOrUnspecifiedIntent(RegisteredClient client) {
        var provider = provider(client);

        var authenticated = provider.authenticate(candidate(client, Map.of(
            SESSION_CLIENT_MARKER, Boolean.TRUE, REFRESH_REQUEST, Boolean.FALSE)));

        assertThat(authenticated).isInstanceOfSatisfying(OAuth2ClientAuthenticationToken.class, token -> {
            assertThat(token.isAuthenticated()).isTrue();
            assertThat(token.getRegisteredClient()).isSameAs(client);
            assertThat(token.getClientAuthenticationMethod()).isEqualTo(ClientAuthenticationMethod.NONE);
        });
        for (Map<String, Object> parameters : List.<Map<String, Object>>of(
            Map.of(SESSION_CLIENT_MARKER, Boolean.TRUE, REFRESH_REQUEST, Boolean.TRUE),
            Map.of(SESSION_CLIENT_MARKER, Boolean.TRUE),
            Map.of(SESSION_CLIENT_MARKER, Boolean.TRUE, REFRESH_REQUEST, "false"))) {
            assertInvalidClient(provider, candidate(client, parameters));
        }
    }

    @ParameterizedTest
    @MethodSource("browserClients")
    void rejectsRevocationWithoutCanonicalBrowserIdentity(RegisteredClient browserClient) {
        RegisteredClient client = RegisteredClient.from(browserClient)
            .clientSettings(ClientSettings.builder().build())
            .build();

        assertInvalidClient(provider(client), candidate(client, Map.of(
            SESSION_CLIENT_MARKER, Boolean.TRUE, REFRESH_REQUEST, Boolean.FALSE)));
    }

    @ParameterizedTest
    @MethodSource("browserClients")
    void rejectsRevocationWithoutPublicClientAuthentication(RegisteredClient browserClient) {
        RegisteredClient client = RegisteredClient.from(browserClient)
            .clientAuthenticationMethods(methods -> {
                methods.clear();
                methods.add(ClientAuthenticationMethod.CLIENT_SECRET_BASIC);
            })
            .build();

        assertInvalidClient(provider(client), candidate(client, Map.of(
            SESSION_CLIENT_MARKER, Boolean.TRUE, REFRESH_REQUEST, Boolean.FALSE)));
    }

    private static Stream<RegisteredClient> browserClients() {
        AuthServiceProperties properties = new AuthServiceProperties();
        properties.getOauth().setIssuer(URI.create("http://localhost:8088/auth-service"));
        properties.getOauth().setPocketHiveBrowserRedirectUri("http://localhost:8088/auth/callback");
        properties.getOauth().setPocketPlannerBrowserRedirectUri("http://localhost:8765/auth/callback");
        properties.getOauth().setPocketDataBrowserRedirectUri("http://localhost:8785/auth/callback");
        return new BrowserOAuthClients(properties).registeredClients().stream();
    }

    private static PocketHivePublicSessionClientAuthenticationProvider provider(RegisteredClient client) {
        return new PocketHivePublicSessionClientAuthenticationProvider(
            new InMemoryRegisteredClientRepository(client));
    }

    private static OAuth2ClientAuthenticationToken candidate(RegisteredClient client, Map<String, Object> parameters) {
        return new OAuth2ClientAuthenticationToken(client.getClientId(), ClientAuthenticationMethod.NONE,
            null, parameters);
    }

    private static void assertInvalidClient(PocketHivePublicSessionClientAuthenticationProvider provider,
                                            OAuth2ClientAuthenticationToken candidate) {
        assertThatThrownBy(() -> provider.authenticate(candidate))
            .isInstanceOfSatisfying(OAuth2AuthenticationException.class,
                error -> assertThat(error.getError().getErrorCode()).isEqualTo(OAuth2ErrorCodes.INVALID_CLIENT));
    }
}
