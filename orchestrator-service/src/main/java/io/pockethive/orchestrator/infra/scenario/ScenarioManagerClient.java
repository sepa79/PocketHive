package io.pockethive.orchestrator.infra.scenario;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.orchestrator.app.ScenarioClient;
import io.pockethive.orchestrator.domain.ScenarioPlan;
import io.pockethive.orchestrator.domain.SwarmTemplate;
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
    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json;
    private final String baseUrl;

    public ScenarioManagerClient(ObjectMapper json) {
        this.json = json;
        this.baseUrl = System.getenv().getOrDefault("SCENARIO_MANAGER_URL", "http://scenario-manager:8080");
    }

    @Override
    public SwarmTemplate fetchTemplate(String templateId) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + "/scenarios/" + templateId))
            .header("Accept", "application/json")
            .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new IllegalStateException("template fetch status " + resp.statusCode());
        }
        ScenarioPlan scenario = json.readValue(resp.body(), ScenarioPlan.class);
        return scenario.template();
    }
}
