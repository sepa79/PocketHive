package io.pockethive.networkproxy.infra.scenario;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.auth.client.AuthServiceServiceTokenProvider;
import io.pockethive.networkproxy.app.NetworkProfileClient;
import io.pockethive.networkproxy.config.NetworkProxyManagerProperties;
import io.pockethive.swarm.model.NetworkProfile;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class ScenarioManagerNetworkProfileClient implements NetworkProfileClient {
    private static final Logger log = LoggerFactory.getLogger(ScenarioManagerNetworkProfileClient.class);

    private final HttpClient http;
    private final ObjectMapper json;
    private final String baseUrl;
    private final Duration requestTimeout;
    private final AuthServiceServiceTokenProvider serviceTokenProvider;

    public ScenarioManagerNetworkProfileClient(ObjectMapper json,
                                              NetworkProxyManagerProperties properties,
                                              AuthServiceServiceTokenProvider serviceTokenProvider) {
        this.json = json;
        this.http = HttpClient.newBuilder()
            .connectTimeout(requirePositive(properties.getHttp().getConnectTimeout(), "http.connect-timeout"))
            .build();
        this.requestTimeout = requirePositive(properties.getHttp().getReadTimeout(), "http.read-timeout");
        this.baseUrl = requireBaseUrl(properties.getScenarioManager().getUrl(),
            "pockethive.network-proxy-manager.scenario-manager.url");
        this.serviceTokenProvider = serviceTokenProvider;
    }

    @Override
    public NetworkProfile fetch(String profileId) throws Exception {
        String trimmedProfileId = requireText(profileId, "profileId");
        String url = baseUrl + "/network-profiles/" + trimmedProfileId;
        log.info("fetching network profile {} from {}", trimmedProfileId, url);
        String authorizationHeader = serviceTokenProvider.getAuthorizationHeader();
        HttpResponse<String> response = sendGet(url, authorizationHeader);
        if (response.statusCode() == 401) {
            log.warn("network profile fetch returned 401 for {}; refreshing service token and retrying once",
                trimmedProfileId);
            response = sendGet(url, serviceTokenProvider.refreshAuthorizationHeader());
        }
        if (response.statusCode() != 200) {
            throw new IllegalStateException("network profile fetch status " + response.statusCode()
                + " for profile " + trimmedProfileId);
        }
        return json.readValue(response.body(), NetworkProfile.class);
    }

    private HttpResponse<String> sendGet(String url, String authorizationHeader) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Accept", "application/json")
            .header("Authorization", authorizationHeader)
            .timeout(requestTimeout)
            .GET()
            .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static Duration requirePositive(Duration value, String field) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalStateException(field + " must be positive");
        }
        return value;
    }

    private static String requireBaseUrl(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(field + " must not be blank");
        }
        return value.trim();
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}
