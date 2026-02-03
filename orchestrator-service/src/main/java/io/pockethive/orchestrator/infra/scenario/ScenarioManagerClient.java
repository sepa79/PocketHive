package io.pockethive.orchestrator.infra.scenario;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.orchestrator.app.ScenarioClient;
import io.pockethive.orchestrator.config.OrchestratorProperties;
import io.pockethive.orchestrator.domain.ScenarioPlan;
import io.pockethive.swarm.model.SutEnvironment;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    public ScenarioManagerClient(ObjectMapper json, OrchestratorProperties properties) {
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
    }

    @Override
    public ScenarioPlan fetchScenario(String templateId) throws Exception {
        String url = baseUrl + "/scenarios/" + templateId;
        HttpResponse<String> resp = sendGet(url, "template " + templateId);
        return json.readValue(resp.body(), ScenarioPlan.class);
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
    public SutEnvironment fetchSutEnvironment(String environmentId) throws Exception {
        String trimmed = environmentId == null ? null : environmentId.trim();
        if (trimmed == null || trimmed.isEmpty()) {
            throw new IllegalArgumentException("environmentId must not be null or blank");
        }
        String url = baseUrl + "/sut-environments/" + trimmed;
        HttpResponse<String> resp = sendGet(url, "sut-environment " + trimmed);
        return json.readValue(resp.body(), SutEnvironment.class);
    }

    private HttpResponse<String> sendGet(String url, String label) throws Exception {
        log.info("fetching {} from {}", label, url);
        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Accept", "application/json")
            .timeout(requestTimeout)
            .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        log.info("{} response status {} length {}", label, resp.statusCode(),
            resp.body() != null ? resp.body().length() : 0);
        if (resp.statusCode() != 200) {
            throw new IllegalStateException(label + " fetch status " + resp.statusCode());
        }
        return resp;
    }

    private HttpResponse<String> sendPost(String url, String label, String body) throws Exception {
        log.info("posting {} to {}", label, url);
        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Accept", "application/json")
            .header("Content-Type", "application/json")
            .timeout(requestTimeout)
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        log.info("{} response status {} length {}", label, resp.statusCode(),
            resp.body() != null ? resp.body().length() : 0);
        if (resp.statusCode() != 200) {
            throw new IllegalStateException(label + " POST status " + resp.statusCode());
        }
        return resp;
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

    public record RuntimeRequest(String swarmId) {
    }

    public record ScenarioRuntimeResponse(String scenarioId, String swarmId, String runtimeDir) {
    }
}
