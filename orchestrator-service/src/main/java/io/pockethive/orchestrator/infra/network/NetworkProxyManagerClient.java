package io.pockethive.orchestrator.infra.network;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.auth.client.AuthServiceServiceTokenProvider;
import io.pockethive.orchestrator.app.NetworkProxyClient;
import io.pockethive.orchestrator.config.OrchestratorProperties;
import io.pockethive.swarm.model.NetworkBinding;
import io.pockethive.swarm.model.NetworkBindingClearRequest;
import io.pockethive.swarm.model.NetworkBindingRequest;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class NetworkProxyManagerClient implements NetworkProxyClient {
    private static final Logger log = LoggerFactory.getLogger(NetworkProxyManagerClient.class);
    private static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(30);

    private final HttpClient http;
    private final ObjectMapper json;
    private final String baseUrl;
    private final Duration requestTimeout;
    private final AuthServiceServiceTokenProvider serviceTokenProvider;

    public NetworkProxyManagerClient(ObjectMapper json,
                                     OrchestratorProperties properties,
                                     org.springframework.beans.factory.ObjectProvider<AuthServiceServiceTokenProvider> serviceTokenProvider) {
        this.json = json;
        OrchestratorProperties.NetworkProxyManager networkProxyManager = properties.getNetworkProxyManager();
        Objects.requireNonNull(networkProxyManager, "networkProxyManager");
        Duration httpConnectTimeout = resolveTimeout(
            networkProxyManager.getHttp().getConnectTimeout(), DEFAULT_CONNECT_TIMEOUT);
        this.http = HttpClient.newBuilder()
            .connectTimeout(httpConnectTimeout)
            .build();
        this.baseUrl = requireBaseUrl(networkProxyManager.getUrl(),
            "pockethive.control-plane.orchestrator.network-proxy-manager.url");
        this.requestTimeout = resolveTimeout(
            networkProxyManager.getHttp().getReadTimeout(), DEFAULT_REQUEST_TIMEOUT);
        this.serviceTokenProvider = serviceTokenProvider.getIfAvailable();
    }

    @Override
    public NetworkBinding bindSwarm(String swarmId,
                                    NetworkBindingRequest request,
                                    String correlationId,
                                    String idempotencyKey) throws Exception {
        String trimmedSwarmId = requireText(swarmId, "swarmId");
        String url = baseUrl + "/api/network/bindings/" + trimmedSwarmId;
        return sendPost(url, "network-binding " + trimmedSwarmId, request, correlationId, idempotencyKey);
    }

    @Override
    public NetworkBinding clearSwarm(String swarmId,
                                     NetworkBindingClearRequest request,
                                     String correlationId,
                                     String idempotencyKey) throws Exception {
        String trimmedSwarmId = requireText(swarmId, "swarmId");
        String url = baseUrl + "/api/network/bindings/" + trimmedSwarmId + "/clear";
        return sendPost(url, "network-binding-clear " + trimmedSwarmId, request, correlationId, idempotencyKey);
    }

    private NetworkBinding sendPost(String url,
                                    String label,
                                    Object payload,
                                    String correlationId,
                                    String idempotencyKey) throws Exception {
        String body = json.writeValueAsString(payload);
        log.info("posting {} to {}", label, url);
        String authorizationHeader = currentAuthorizationHeader(false);
        HttpResponse<String> response = sendPostOnce(url, body, correlationId, idempotencyKey, authorizationHeader);
        if (response.statusCode() == 401 && serviceTokenProvider != null && authorizationHeader != null) {
            log.warn("{} returned 401 on POST; refreshing service token and retrying once", label);
            response = sendPostOnce(url, body, correlationId, idempotencyKey, currentAuthorizationHeader(true));
        }
        log.info("{} response status {} length {}", label, response.statusCode(),
            response.body() != null ? response.body().length() : 0);
        if (response.statusCode() != 200) {
            throw new IllegalStateException(label + " POST status " + response.statusCode());
        }
        return json.readValue(response.body(), NetworkBinding.class);
    }

    private HttpResponse<String> sendPostOnce(String url,
                                              String body,
                                              String correlationId,
                                              String idempotencyKey,
                                              String authorizationHeader) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Accept", "application/json")
            .header("Content-Type", "application/json")
            .timeout(requestTimeout)
            .POST(HttpRequest.BodyPublishers.ofString(body));
        applyAuthorization(builder, authorizationHeader);
        if (correlationId != null && !correlationId.isBlank()) {
            builder.header("X-Correlation-Id", correlationId);
        }
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            builder.header("X-Idempotency-Key", idempotencyKey);
        }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static Duration resolveTimeout(Duration candidate, Duration fallback) {
        if (candidate == null || candidate.isZero() || candidate.isNegative()) {
            return fallback;
        }
        return candidate;
    }

    private static String requireBaseUrl(String baseUrl, String propertyName) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalStateException(propertyName + " must not be null or blank");
        }
        return baseUrl;
    }

    private void applyAuthorization(HttpRequest.Builder builder, String authorizationHeader) {
        if (authorizationHeader != null) {
            builder.header("Authorization", authorizationHeader);
        }
    }

    private String currentAuthorizationHeader(boolean forceRefresh) {
        if (serviceTokenProvider == null) {
            return null;
        }
        return forceRefresh
            ? serviceTokenProvider.refreshAuthorizationHeader()
            : serviceTokenProvider.getAuthorizationHeader();
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}
