package io.pockethive.orchestrator.infra.scenario;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.auth.client.AuthServiceServiceTokenProvider;
import io.pockethive.orchestrator.app.ScenarioClient;
import io.pockethive.orchestrator.app.ScenarioClientException;
import io.pockethive.orchestrator.config.OrchestratorProperties;
import io.pockethive.orchestrator.domain.ScenarioPlan;
import io.pockethive.swarm.model.NetworkProfile;
import io.pockethive.swarm.model.SutEnvironment;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
 
/**
 * HTTP client to retrieve templates and SUT environments from scenario-manager-service.
 */
@Component
public class ScenarioManagerClient implements ScenarioClient {
    private static final Logger log = LoggerFactory.getLogger(ScenarioManagerClient.class);
    private static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(30);

    private final HttpClient http;
    private final ObjectMapper json;
    private final String baseUrl;
    private final Duration requestTimeout;
    private final AuthServiceServiceTokenProvider serviceTokenProvider;

    public ScenarioManagerClient(ObjectMapper json,
                                 OrchestratorProperties properties,
                                 org.springframework.beans.factory.ObjectProvider<AuthServiceServiceTokenProvider> serviceTokenProvider) {
        this.json = json;
        OrchestratorProperties.ScenarioManager scenario = properties.getScenarioManager();
        Objects.requireNonNull(scenario, "scenario");
        Duration httpConnectTimeout = resolveTimeout(
            scenario.getHttp().getConnectTimeout(), DEFAULT_CONNECT_TIMEOUT);
        this.http = HttpClient.newBuilder()
            .connectTimeout(httpConnectTimeout)
            .build();
        this.baseUrl = requireBaseUrl(scenario.getUrl());
        this.requestTimeout = resolveTimeout(
            scenario.getHttp().getReadTimeout(), DEFAULT_REQUEST_TIMEOUT);
        this.serviceTokenProvider = serviceTokenProvider.getIfAvailable();
    }

    @Override
    public ScenarioPlan fetchScenario(String templateId) throws Exception {
        String url = baseUrl + "/scenarios/" + templateId;
        HttpResponse<String> resp = sendGet(url, "template " + templateId);
        return json.readValue(resp.body(), ScenarioPlan.class);
    }

    @Override
    public ScenarioTemplateDescriptor fetchScenarioTemplate(String templateId) throws Exception {
        String trimmedTemplate = templateId == null ? null : templateId.trim();
        if (trimmedTemplate == null || trimmedTemplate.isEmpty()) {
            throw new IllegalArgumentException("templateId must not be null or blank");
        }
        String url = baseUrl + "/api/templates/" + trimmedTemplate;
        HttpResponse<String> resp = sendGet(url, "template-metadata " + trimmedTemplate);
        ScenarioTemplateResponse body = json.readValue(resp.body(), ScenarioTemplateResponse.class);
        return new ScenarioTemplateDescriptor(body.id(), body.bundleKey(), body.bundlePath(), body.folderPath(), body.defunct());
    }

    @Override
    public String prepareScenarioRuntime(String templateId, String swarmId) throws Exception {
        String trimmedTemplate = templateId == null ? null : templateId.trim();
        if (trimmedTemplate == null || trimmedTemplate.isEmpty()) {
            throw new IllegalArgumentException("templateId must not be null or blank");
        }
        String trimmedSwarm = swarmId == null ? null : swarmId.trim();
        if (trimmedSwarm == null || trimmedSwarm.isEmpty()) {
            throw new IllegalArgumentException("swarmId must not be null or blank");
        }
        String url = baseUrl + "/scenarios/" + trimmedTemplate + "/runtime";
        RuntimeRequest body = new RuntimeRequest(trimmedSwarm);
        String jsonBody = json.writeValueAsString(body);
        HttpResponse<String> resp = sendPost(url, "scenario-runtime " + trimmedTemplate + "/" + trimmedSwarm, jsonBody);
        ScenarioRuntimeResponse descriptor = json.readValue(resp.body(), ScenarioRuntimeResponse.class);
        String runtimeDir = descriptor.runtimeDir();
        if (runtimeDir == null || runtimeDir.isBlank()) {
            throw new IllegalStateException(
                "Scenario runtime for template '%s' and swarm '%s' returned empty runtimeDir"
                    .formatted(trimmedTemplate, trimmedSwarm));
        }
        return runtimeDir;
    }

    @Override
    public SutEnvironment fetchScenarioSut(String templateId,
                                           String sutId,
                                           String correlationId,
                                           String idempotencyKey) throws Exception {
        String tpl = templateId == null ? null : templateId.trim();
        if (tpl == null || tpl.isEmpty()) {
            throw new IllegalArgumentException("templateId must not be null or blank");
        }
        String sut = sutId == null ? null : sutId.trim();
        if (sut == null || sut.isEmpty()) {
            throw new IllegalArgumentException("sutId must not be null or blank");
        }
        String url = baseUrl + "/scenarios/" + tpl + "/suts/" + sut;
        HttpResponse<String> resp = sendGet(url, "scenario-sut " + tpl + "/" + sut, correlationId, idempotencyKey);
        return json.readValue(resp.body(), SutEnvironment.class);
    }

    @Override
    public ResolvedVariables resolveScenarioVariables(String templateId,
                                                      String profileId,
                                                      String sutId,
                                                      String correlationId,
                                                      String idempotencyKey) throws Exception {
        String tpl = templateId == null ? null : templateId.trim();
        if (tpl == null || tpl.isEmpty()) {
            throw new IllegalArgumentException("templateId must not be null or blank");
        }
        String prof = profileId == null ? null : profileId.trim();
        if (prof != null && prof.isEmpty()) {
            prof = null;
        }
        String sut = sutId == null ? null : sutId.trim();
        if (sut != null && sut.isEmpty()) {
            sut = null;
        }
        String url = baseUrl + "/scenarios/" + tpl + "/variables/resolve";
        if (prof != null || sut != null) {
            StringBuilder sb = new StringBuilder(url);
            sb.append('?');
            boolean first = true;
            if (prof != null) {
                sb.append("profileId=").append(java.net.URLEncoder.encode(prof, java.nio.charset.StandardCharsets.UTF_8));
                first = false;
            }
            if (sut != null) {
                if (!first) {
                    sb.append('&');
                }
                sb.append("sutId=").append(java.net.URLEncoder.encode(sut, java.nio.charset.StandardCharsets.UTF_8));
            }
            url = sb.toString();
        }
        HttpResponse<String> resp = sendGet(url, "scenario-variables " + tpl, correlationId, idempotencyKey);
        ScenarioVariablesResolveResponse body = json.readValue(resp.body(), ScenarioVariablesResolveResponse.class);
        Map<String, Object> vars = body.vars() == null ? Map.of() : body.vars();
        java.util.List<String> warnings = body.warnings() == null ? java.util.List.of() : body.warnings();
        return new ResolvedVariables(body.profileId(), body.sutId(), vars, warnings);
    }

    @Override
    public NetworkProfile fetchNetworkProfile(String profileId,
                                              String correlationId,
                                              String idempotencyKey) throws Exception {
        String profile = profileId == null ? null : profileId.trim();
        if (profile == null || profile.isEmpty()) {
            throw new IllegalArgumentException("profileId must not be null or blank");
        }
        String url = baseUrl + "/network-profiles/" + profile;
        HttpResponse<String> resp = sendGet(url, "network-profile " + profile, correlationId, idempotencyKey);
        return json.readValue(resp.body(), NetworkProfile.class);
    }

    private HttpResponse<String> sendGet(String url, String label) throws Exception {
        return sendGet(url, label, null, null);
    }

    private HttpResponse<String> sendGet(String url, String label, String correlationId, String idempotencyKey) throws Exception {
        log.info("fetching {} from {}", label, url);
        String authorizationHeader = currentAuthorizationHeader(false);
        HttpResponse<String> resp = sendGetOnce(url, authorizationHeader, correlationId, idempotencyKey);
        if (resp.statusCode() == 401 && serviceTokenProvider != null && authorizationHeader != null) {
            log.warn("{} returned 401; refreshing service token and retrying once", label);
            resp = sendGetOnce(url, currentAuthorizationHeader(true), correlationId, idempotencyKey);
        }
        log.info("{} response status {} length {}", label, resp.statusCode(),
            resp.body() != null ? resp.body().length() : 0);
        if (resp.statusCode() != 200) {
            throw requestFailure(label + " fetch", resp);
        }
        return resp;
    }

    private HttpResponse<String> sendPost(String url, String label, String body) throws Exception {
        log.info("posting {} to {}", label, url);
        String authorizationHeader = currentAuthorizationHeader(false);
        HttpResponse<String> resp = sendPostOnce(url, authorizationHeader, body);
        if (resp.statusCode() == 401 && serviceTokenProvider != null && authorizationHeader != null) {
            log.warn("{} returned 401 on POST; refreshing service token and retrying once", label);
            resp = sendPostOnce(url, currentAuthorizationHeader(true), body);
        }
        log.info("{} response status {} length {}", label, resp.statusCode(),
            resp.body() != null ? resp.body().length() : 0);
        if (resp.statusCode() != 200) {
            throw requestFailure(label + " POST", resp);
        }
        return resp;
    }

    private static ScenarioClientException requestFailure(String label, HttpResponse<String> response) {
        return new ScenarioClientException(
            label,
            response.statusCode(),
            response.body(),
            response.headers().firstValue(HttpHeaders.CONTENT_TYPE).orElse(null));
    }

    private HttpResponse<String> sendGetOnce(String url,
                                             String authorizationHeader,
                                             String correlationId,
                                             String idempotencyKey) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Accept", "application/json")
            .timeout(requestTimeout);
        applyAuthorization(builder, authorizationHeader);
        if (correlationId != null && !correlationId.isBlank()) {
            builder.header("X-Correlation-Id", correlationId);
        }
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            builder.header("X-Idempotency-Key", idempotencyKey);
        }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> sendPostOnce(String url, String authorizationHeader, String body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Accept", "application/json")
            .header("Content-Type", "application/json")
            .timeout(requestTimeout)
            .POST(HttpRequest.BodyPublishers.ofString(body));
        applyAuthorization(builder, authorizationHeader);
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static Duration resolveTimeout(Duration candidate, Duration fallback) {
        if (candidate == null || candidate.isZero() || candidate.isNegative()) {
            return fallback;
        }
        return candidate;
    }

    private static String requireBaseUrl(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalStateException(
                "pockethive.control-plane.orchestrator.scenario-manager.url must not be null or blank");
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

    public record RuntimeRequest(String swarmId) {
    }

    public record ScenarioRuntimeResponse(String scenarioId, String swarmId, String runtimeDir) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ScenarioTemplateResponse(String id, String bundleKey, String bundlePath, String folderPath, boolean defunct) {
    }

    public record ScenarioVariablesResolveResponse(String profileId, String sutId, Map<String, Object> vars, java.util.List<String> warnings) {
    }
}
