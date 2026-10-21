package io.pockethive.auth.service.oauth;

import io.pockethive.auth.contract.PocketHiveBrowserAuth;
import io.pockethive.auth.contract.PocketHiveDeviceAuth;
import io.pockethive.auth.contract.PublicEndpointTransportPolicy;
import io.pockethive.auth.service.config.AuthServiceProperties;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;
import org.springframework.security.oauth2.server.authorization.settings.OAuth2TokenFormat;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * Responsibility: Own explicit first-party browser clients and their public config projection.
 * Must not: Authenticate users, issue tokens or infer redirect destinations from HTTP input.
 * Contract: RESP-BROWSER-SSO — docs/architecture/runtime-responsibilities.md#resp-browser-sso.
 */
@Component
public final class BrowserOAuthClients {
    private final String issuer;
    private final List<RegisteredClient> clients;

    public BrowserOAuthClients(AuthServiceProperties properties) {
        issuer = properties.getOauth().getIssuer().toString();
        List<RegisteredClient> configured = new ArrayList<>();
        add(configured, properties, PocketHiveBrowserAuth.PH_CLIENT_ID, "PocketHive",
            properties.getOauth().getPocketHiveBrowserRedirectUri());
        add(configured, properties, PocketHiveBrowserAuth.PP_CLIENT_ID, "PocketPlanner",
            properties.getOauth().getPocketPlannerBrowserRedirectUri());
        add(configured, properties, PocketHiveBrowserAuth.PD_CLIENT_ID, "PocketData",
            properties.getOauth().getPocketDataBrowserRedirectUri());
        addDevice(configured, properties, PocketHiveDeviceAuth.HIVE_CLIENT_ID, "PocketHive");
        addDevice(configured, properties, PocketHiveDeviceAuth.PLANNER_CLIENT_ID, "PocketPlanner");
        addDevice(configured, properties, PocketHiveDeviceAuth.DATA_CLIENT_ID, "PocketData");
        clients = List.copyOf(configured);
    }

    public List<RegisteredClient> registeredClients() { return clients; }

    public Map<String, String> config(String clientId) {
        RegisteredClient client = requireRedirectClient(clientId);
        return Map.of("clientId", client.getClientId(), "issuer", issuer,
            "redirectUri", client.getRedirectUris().iterator().next(),
            "resource", PocketHiveBrowserAuth.RESOURCE, "scope", PocketHiveBrowserAuth.SCOPE);
    }

    public RegisteredClient requireRedirectClient(String clientId) {
        RegisteredClient client = require(clientId);
        if (client.getRedirectUris().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "This client uses device confirmation, not redirects");
        }
        return client;
    }

    public RegisteredClient require(String clientId) {
        return clients.stream().filter(client -> client.getClientId().equals(clientId)).findFirst()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Browser client is not configured"));
    }

    public static boolean isBrowser(RegisteredClient client) {
        return Boolean.TRUE.equals(client.getClientSettings().getSetting(PocketHiveBrowserAuth.CLIENT_SETTING));
    }

    public static boolean isDevice(RegisteredClient client) {
        return client != null && isBrowser(client)
            && client.getAuthorizationGrantTypes().contains(AuthorizationGrantType.DEVICE_CODE)
            && client.getClientAuthenticationMethods().contains(ClientAuthenticationMethod.NONE);
    }

    public RegisteredClient requireDeviceClient(String clientId) {
        RegisteredClient client = require(clientId);
        if (!isDevice(client)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Not a device-login client");
        }
        return client;
    }

    private static void addDevice(List<RegisteredClient> clients, AuthServiceProperties properties,
                                  String clientId, String name) {
        clients.add(RegisteredClient.withId("browser-" + clientId).clientId(clientId).clientName(name)
            .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
            .authorizationGrantType(AuthorizationGrantType.DEVICE_CODE)
            .scope(PocketHiveBrowserAuth.SCOPE)
            .clientSettings(ClientSettings.builder().requireAuthorizationConsent(true)
                .setting(PocketHiveBrowserAuth.CLIENT_SETTING, true).build())
            .tokenSettings(TokenSettings.builder().accessTokenFormat(OAuth2TokenFormat.REFERENCE)
                .deviceCodeTimeToLive(properties.getOauth().getDeviceCodeTtl())
                .accessTokenTimeToLive(properties.getOauth().getAccessTokenTtl()).build())
            .build());
    }

    private static void add(List<RegisteredClient> clients, AuthServiceProperties properties,
                            String clientId, String name, String redirect) {
        if (redirect == null || redirect.isEmpty()) return;
        URI uri;
        try { uri = URI.create(redirect); }
        catch (IllegalArgumentException error) { throw new IllegalStateException("Invalid browser callback configuration", error); }
        if (!PublicEndpointTransportPolicy.allows(uri, false) || !"/auth/callback".equals(uri.getPath())
            || uri.getQuery() != null || uri.getFragment() != null || uri.getUserInfo() != null) {
            throw new IllegalStateException("Browser callback must be exact HTTPS or HTTP loopback /auth/callback");
        }
        clients.add(RegisteredClient.withId("browser-" + clientId).clientId(clientId).clientName(name)
            .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
            .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
            .redirectUri(redirect).scope(PocketHiveBrowserAuth.SCOPE)
            .clientSettings(ClientSettings.builder().requireProofKey(true).requireAuthorizationConsent(false)
                .setting(PocketHiveBrowserAuth.CLIENT_SETTING, true).build())
            .tokenSettings(TokenSettings.builder()
                .accessTokenFormat(OAuth2TokenFormat.REFERENCE)
                .authorizationCodeTimeToLive(properties.getOauth().getAuthorizationCodeTtl())
                .accessTokenTimeToLive(properties.getOauth().getAccessTokenTtl()).build())
            .build());
    }
}
