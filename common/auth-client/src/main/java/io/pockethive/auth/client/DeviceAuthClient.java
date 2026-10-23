package io.pockethive.auth.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.auth.contract.DeviceAuthorizationDto;
import io.pockethive.auth.contract.DeviceClientConfigDto;
import io.pockethive.auth.contract.DeviceTokenDto;
import io.pockethive.auth.contract.PocketHiveBrowserAuth;
import io.pockethive.auth.contract.PocketHiveDeviceAuth;
import io.pockethive.auth.contract.PublicEndpointTransportPolicy;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.Set;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * Responsibility: Execute typed first-party device authorization, polling and revocation requests.
 * Must not: Poll in a loop, store tokens, issue identity or decide product policy.
 * Contract: RESP-BROWSER-DEVICE-LOGIN — docs/architecture/runtime-responsibilities.md#resp-browser-device-login.
 */
public final class DeviceAuthClient {
    private static final String DEVICE_GRANT = "urn:ietf:params:oauth:grant-type:device_code";
    private static final Set<String> PROTOCOL_ERRORS = Set.of("authorization_pending", "slow_down",
        "access_denied", "expired_token", "invalid_grant", "invalid_client", "invalid_request", "invalid_scope");
    private final RestClient http;
    private final String clientId;
    private final boolean allowRemoteHttp;
    private final ObjectMapper errors = new ObjectMapper();

    public DeviceAuthClient(URI baseUrl, String clientId, Duration connectTimeout, Duration readTimeout,
                            boolean allowRemoteHttp) {
        if (!Set.of(PocketHiveDeviceAuth.DATA_CLIENT_ID, PocketHiveDeviceAuth.PLANNER_CLIENT_ID).contains(clientId)) {
            throw new IllegalArgumentException("An explicit Pocket device client is required");
        }
        this.clientId = clientId;
        this.allowRemoteHttp = allowRemoteHttp;
        http = AuthServiceRestClients.create(baseUrl, connectTimeout, readTimeout);
    }

    public DeviceAuthorizationDto start() {
        var form = form();
        form.add("resource", PocketHiveBrowserAuth.RESOURCE);
        form.add("scope", PocketHiveBrowserAuth.SCOPE);
        var authorization = post(PocketHiveDeviceAuth.AUTHORIZATION_PATH, form, DeviceAuthorizationDto.class);
        if (!PublicEndpointTransportPolicy.allows(authorization.verificationUri(), allowRemoteHttp)) {
            throw new AuthServiceClientException("Invalid PH device authorization transport", 503, null);
        }
        return authorization;
    }

    public DeviceTokenDto poll(String deviceCode) {
        if (deviceCode == null || deviceCode.isBlank() || deviceCode.length() > 4096) {
            throw new IllegalArgumentException("A device code is required");
        }
        var form = form();
        form.add("grant_type", DEVICE_GRANT);
        form.add("device_code", deviceCode);
        form.add("resource", PocketHiveBrowserAuth.RESOURCE);
        return post("/oauth/token", form, DeviceTokenDto.class);
    }

    public void revoke(String token) {
        if (token == null || !token.startsWith(PocketHiveBrowserAuth.TOKEN_PREFIX) || token.length() > 16_000) {
            throw new IllegalArgumentException("A PH browser token is required");
        }
        var form = form();
        form.add("token", token);
        form.add("token_type_hint", "access_token");
        post("/oauth/revoke", form, Void.class);
    }

    public DeviceClientConfigDto configuration() {
        try {
            var configuration = http.get().uri(PocketHiveDeviceAuth.CONFIG_PATH + "?client_id=" + clientId)
                .retrieve().body(DeviceClientConfigDto.class);
            if (configuration == null
                || !PublicEndpointTransportPolicy.allows(configuration.logoutUri().resolve("/"), allowRemoteHttp)
                || !("client_id=" + clientId).equals(configuration.logoutUri().getRawQuery())) {
                throw new AuthServiceClientException("Invalid PH device configuration", 503, null);
            }
            return configuration;
        } catch (RestClientException failure) {
            throw new AuthServiceClientException("PH device configuration unavailable", 503, null);
        }
    }

    private MultiValueMap<String, String> form() {
        var form = new LinkedMultiValueMap<String, String>();
        form.add("client_id", clientId);
        return form;
    }

    private <Response> Response post(String path, MultiValueMap<String, String> form, Class<Response> type) {
        try {
            Response result = http.post().uri(path).contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form).retrieve().body(type);
            if (result == null && type != Void.class) {
                throw new AuthServiceClientException("Empty PH device response", 503, null);
            }
            return result;
        } catch (RestClientResponseException failure) {
            if (failure.getStatusCode().value() == 400 || failure.getStatusCode().value() == 401) {
                try {
                    JsonNode document = errors.readTree(failure.getResponseBodyAsByteArray());
                    JsonNode error = document == null ? null : document.get("error");
                    if (error != null && error.isTextual() && PROTOCOL_ERRORS.contains(error.textValue())) {
                        throw new DeviceAuthException(error.textValue(), failure.getStatusCode().value());
                    }
                } catch (IOException ignored) {
                    throw new AuthServiceClientException("Invalid PH OAuth error response", 503, null);
                }
            }
            throw new AuthServiceClientException("PH device request unavailable", 503, null);
        } catch (RestClientException failure) {
            throw new AuthServiceClientException("PH device request unavailable", 503, null);
        }
    }
}
