package io.pockethive.auth.service.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.pockethive.auth.contract.PocketHiveBrowserAuth;
import io.pockethive.auth.contract.PocketHiveDeviceAuth;
import io.pockethive.auth.service.config.AuthServiceProperties;
import java.net.URI;
import java.time.Duration;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.settings.OAuth2TokenFormat;
import org.springframework.web.server.ResponseStatusException;

class BrowserOAuthClientsTest {
    @ParameterizedTest
    @CsvSource({
        PocketHiveDeviceAuth.HIVE_CLIENT_ID + ",PocketHive",
        PocketHiveDeviceAuth.PLANNER_CLIENT_ID + ",PocketPlanner",
        PocketHiveDeviceAuth.DATA_CLIENT_ID + ",PocketData"
    })
    void deviceClientsRequireConsentAndUseOnlyTheStandardCallbackFreeGrant(String clientId, String name) {
        var properties = new AuthServiceProperties();
        properties.getOauth().setIssuer(URI.create("https://ph.example.test/auth-service"));
        properties.getOauth().setDeviceCodeTtl(Duration.ofSeconds(123));
        properties.getOauth().setAccessTokenTtl(Duration.ofMinutes(17));
        var clients = new BrowserOAuthClients(properties);
        var client = clients.requireDeviceClient(clientId);

        assertThat(client.getClientName()).isEqualTo(name);
        assertThat(client.getClientAuthenticationMethods()).containsExactly(ClientAuthenticationMethod.NONE);
        assertThat(client.getClientSecret()).isNull();
        assertThat(client.getAuthorizationGrantTypes()).containsExactly(AuthorizationGrantType.DEVICE_CODE);
        assertThat(client.getRedirectUris()).isEmpty();
        assertThat(client.getPostLogoutRedirectUris()).isEmpty();
        assertThat(client.getScopes()).containsExactly(PocketHiveBrowserAuth.SCOPE);
        assertThat(client.getClientSettings().isRequireAuthorizationConsent()).isTrue();
        assertThat(BrowserOAuthClients.isBrowser(client)).isTrue();
        assertThat(client.getTokenSettings().getAccessTokenFormat()).isEqualTo(OAuth2TokenFormat.REFERENCE);
        assertThat(client.getTokenSettings().getDeviceCodeTimeToLive()).isEqualTo(properties.getOauth().getDeviceCodeTtl());
        assertThat(client.getTokenSettings().getAccessTokenTimeToLive()).isEqualTo(properties.getOauth().getAccessTokenTtl());
        assertThatThrownBy(() -> clients.requireRedirectClient(clientId))
            .isInstanceOfSatisfying(ResponseStatusException.class,
                error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
    }
}
