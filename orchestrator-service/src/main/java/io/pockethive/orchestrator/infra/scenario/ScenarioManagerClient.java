package io.pockethive.orchestrator.infra.scenario;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.orchestrator.app.ScenarioClient;
import io.pockethive.orchestrator.config.OrchestratorProperties;
import io.pockethive.orchestrator.domain.ScenarioPlan;
import io.pockethive.swarm.model.SutEnvironment;
import java.util.Objects;
import java.util.Map;
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

    private HttpResponse<String> sendGet(String url, String label) throws Exception {
        return sendGet(url, label, null, null);
    }

    private HttpResponse<String> sendGet(String url, String label, String correlationId, String idempotencyKey) throws Exception {
        log.info("fetching {} from {}", label, url);
        HttpRequest.Builder builder = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Accept", "application/json")
            .timeout(requestTimeout)
            ;
        if (correlationId != null && !correlationId.isBlank()) {
            builder.header("X-Correlation-Id", correlationId);
        }
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            builder.header("X-Idempotency-Key", idempotencyKey);
        }
        HttpRequest req = builder.build();
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

    public record ScenarioVariablesResolveResponse(String profileId, String sutId, Map<String, Object> vars, java.util.List<String> warnings) {
    }
}
