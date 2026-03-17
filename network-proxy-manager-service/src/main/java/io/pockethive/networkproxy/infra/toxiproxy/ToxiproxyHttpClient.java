package io.pockethive.networkproxy.infra.toxiproxy;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.networkproxy.app.ToxiproxyAdminClient;
import io.pockethive.networkproxy.config.NetworkProxyManagerProperties;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class ToxiproxyHttpClient implements ToxiproxyAdminClient {
    private static final Logger log = LoggerFactory.getLogger(ToxiproxyHttpClient.class);

    private final HttpClient http;
    private final ObjectMapper json;
    private final String baseUrl;
    private final Duration requestTimeout;

    public ToxiproxyHttpClient(ObjectMapper json, NetworkProxyManagerProperties properties) {
        this.json = json;
        this.http = HttpClient.newBuilder()
            .connectTimeout(requirePositive(properties.getHttp().getConnectTimeout(), "http.connect-timeout"))
            .build();
        this.requestTimeout = requirePositive(properties.getHttp().getReadTimeout(), "http.read-timeout");
        this.baseUrl = requireBaseUrl(properties.getToxiproxy().getUrl(),
            "pockethive.network-proxy-manager.toxiproxy.url");
    }

    @Override
    public Map<String, ProxyRecord> listProxies() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + "/proxies"))
            .header("Accept", "application/json")
            .timeout(requestTimeout)
            .GET()
            .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("toxiproxy list proxies status " + response.statusCode());
        }
        Map<String, ProxyResponse> proxies = json.readValue(
            response.body(),
            new TypeReference<LinkedHashMap<String, ProxyResponse>>() {});
        Map<String, ProxyRecord> byName = new LinkedHashMap<>();
        if (proxies != null) {
            for (Map.Entry<String, ProxyResponse> entry : proxies.entrySet()) {
                ProxyResponse proxy = entry.getValue();
                String proxyName = proxy.name() == null || proxy.name().isBlank()
                    ? entry.getKey()
                    : proxy.name();
                byName.put(proxyName, new ProxyRecord(proxyName, proxy.listen(), proxy.upstream(), proxy.enabled()));
            }
        }
        return byName;
    }

    @Override
    public void deleteProxy(String proxyName) throws Exception {
        String trimmedName = requireText(proxyName, "proxyName");
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + "/proxies/" + trimmedName))
            .timeout(requestTimeout)
            .DELETE()
            .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == HttpStatus.NOT_FOUND.value()) {
            return;
        }
        if (response.statusCode() != HttpStatus.NO_CONTENT.value()) {
            throw new IllegalStateException("toxiproxy delete proxy status " + response.statusCode()
                + " for proxy " + trimmedName);
        }
    }

    @Override
    public ProxyRecord createProxy(ProxyRecord proxy) throws Exception {
        CreateProxyRequest payload = new CreateProxyRequest(
            requireText(proxy.name(), "proxy.name"),
            requireText(proxy.listen(), "proxy.listen"),
            requireText(proxy.upstream(), "proxy.upstream"),
            proxy.enabled());
        HttpResponse<String> response = sendJson("POST", baseUrl + "/proxies", payload);
        if (response.statusCode() != HttpStatus.CREATED.value()) {
            throw new IllegalStateException("toxiproxy create proxy status " + response.statusCode()
                + " for proxy " + payload.name());
        }
        ProxyResponse created = json.readValue(response.body(), ProxyResponse.class);
        return new ProxyRecord(created.name(), created.listen(), created.upstream(), created.enabled());
    }

    @Override
    public ToxicRecord createToxic(String proxyName, ToxicRecord toxic) throws Exception {
        String trimmedProxyName = requireText(proxyName, "proxyName");
        CreateToxicRequest payload = new CreateToxicRequest(
            requireText(toxic.name(), "toxic.name"),
            requireText(toxic.type(), "toxic.type"),
            requireText(toxic.stream(), "toxic.stream"),
            toxic.toxicity(),
            toxic.attributes());
        HttpResponse<String> response = sendJson("POST", baseUrl + "/proxies/" + trimmedProxyName + "/toxics", payload);
        if (response.statusCode() != HttpStatus.CREATED.value() && response.statusCode() != HttpStatus.OK.value()) {
            throw new IllegalStateException("toxiproxy create toxic status " + response.statusCode()
                + " for proxy " + trimmedProxyName + " toxic " + payload.name());
        }
        ToxicResponse created = json.readValue(response.body(), ToxicResponse.class);
        return new ToxicRecord(created.name(), created.type(), created.stream(), created.toxicity(), created.attributes());
    }

    @Override
    public void deleteToxic(String proxyName, String toxicName) throws Exception {
        String trimmedProxyName = requireText(proxyName, "proxyName");
        String trimmedToxicName = requireText(toxicName, "toxicName");
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + "/proxies/" + trimmedProxyName + "/toxics/" + trimmedToxicName))
            .timeout(requestTimeout)
            .DELETE()
            .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == HttpStatus.NOT_FOUND.value()) {
            return;
        }
        if (response.statusCode() != HttpStatus.NO_CONTENT.value()) {
            throw new IllegalStateException("toxiproxy delete toxic status " + response.statusCode()
                + " for proxy " + trimmedProxyName + " toxic " + trimmedToxicName);
        }
    }

    private HttpResponse<String> sendJson(String method, String url, Object payload) throws Exception {
        String body = json.writeValueAsString(payload);
        log.info("toxiproxy {} {}", method, url);
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Accept", "application/json")
            .header("Content-Type", "application/json")
            .timeout(requestTimeout)
            .method(method, HttpRequest.BodyPublishers.ofString(body))
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

    private record CreateProxyRequest(String name, String listen, String upstream, boolean enabled) {
    }

    private record CreateToxicRequest(String name,
                                      String type,
                                      String stream,
                                      double toxicity,
                                      Map<String, Object> attributes) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ProxyResponse(String name,
                                 String listen,
                                 String upstream,
                                 boolean enabled,
                                 List<ToxicResponse> toxics) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ToxicResponse(String name,
                                 String type,
                                 String stream,
                                 double toxicity,
                                 Map<String, Object> attributes) {
    }
}
