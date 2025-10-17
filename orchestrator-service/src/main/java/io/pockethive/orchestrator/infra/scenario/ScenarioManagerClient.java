package io.pockethive.orchestrator.infra.scenario;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.orchestrator.app.ScenarioClient;
import io.pockethive.orchestrator.config.OrchestratorProperties;
import io.pockethive.orchestrator.domain.ScenarioPlan;
import io.pockethive.swarm.model.SwarmTemplate;
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
 * HTTP client to retrieve templates from scenario-manager-service.
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
    public SwarmTemplate fetchTemplate(String templateId) throws Exception {
        String url = baseUrl + "/scenarios/" + templateId;
        log.info("fetching template {} from {}", templateId, url);
        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Accept", "application/json")
            .timeout(requestTimeout)
            .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        log.info("template response status {} length {}", resp.statusCode(), resp.body() != null ? resp.body().length() : 0);
        if (resp.statusCode() != 200) {
            throw new IllegalStateException("template fetch status " + resp.statusCode());
        }
        ScenarioPlan scenario = json.readValue(resp.body(), ScenarioPlan.class);
        return scenario.template();
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
}
