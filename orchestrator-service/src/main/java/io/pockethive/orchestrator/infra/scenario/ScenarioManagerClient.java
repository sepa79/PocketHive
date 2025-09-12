package io.pockethive.orchestrator.infra.scenario;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.orchestrator.app.ScenarioClient;
import io.pockethive.orchestrator.domain.ScenarioPlan;
import io.pockethive.orchestrator.domain.SwarmTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/**
 * HTTP client to retrieve templates from scenario-manager-service.
 */
@Component
public class ScenarioManagerClient implements ScenarioClient {
    private static final Logger log = LoggerFactory.getLogger(ScenarioManagerClient.class);
    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json;
    private final String baseUrl;

    public ScenarioManagerClient(ObjectMapper json) {
        this.json = json;
        this.baseUrl = System.getenv().getOrDefault("SCENARIO_MANAGER_URL", "http://scenario-manager:8080");
    }

    @Override
    public SwarmTemplate fetchTemplate(String templateId) throws Exception {
        String url = baseUrl + "/scenarios/" + templateId;
        log.info("fetching template {} from {}", templateId, url);
        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Accept", "application/json")
            .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        log.info("template response status {} length {}", resp.statusCode(), resp.body() != null ? resp.body().length() : 0);
        if (resp.statusCode() != 200) {
            throw new IllegalStateException("template fetch status " + resp.statusCode());
        }
        ScenarioPlan scenario = json.readValue(resp.body(), ScenarioPlan.class);
        return scenario.template();
    }
}
