package io.pockethive.e2e.clients;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.reactive.function.client.WebClient;

import io.pockethive.swarm.model.SwarmTemplate;

/**
 * Simple client used to browse the Scenario Manager catalogue in acceptance tests.
 */
public final class ScenarioManagerClient {

  private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(20);

  private final WebClient webClient;

  private ScenarioManagerClient(WebClient webClient) {
    this.webClient = webClient;
  }

  public static ScenarioManagerClient create(URI baseUrl) {
    Objects.requireNonNull(baseUrl, "baseUrl");
    return new ScenarioManagerClient(WebClient.builder().baseUrl(baseUrl.toString()).build());
  }

  public WebClient webClient() {
    return webClient;
  }

  public List<ScenarioSummary> listScenarios() {
    ResponseEntity<List<ScenarioSummary>> entity = webClient.get()
        .uri("/scenarios")
        .accept(MediaType.APPLICATION_JSON)
        .retrieve()
        .toEntity(new ParameterizedTypeReference<List<ScenarioSummary>>() {})
        .timeout(HTTP_TIMEOUT)
        .block(HTTP_TIMEOUT);
    if (entity == null || entity.getBody() == null) {
      throw new IllegalStateException("Scenario Manager /scenarios returned no body");
    }
    return entity.getBody();
  }

  public ScenarioDetails fetchScenario(String scenarioId) {
    Objects.requireNonNull(scenarioId, "scenarioId");
    ResponseEntity<ScenarioDetails> entity = webClient.get()
        .uri(uriBuilder -> uriBuilder.path("/scenarios/{id}").build(scenarioId))
        .accept(MediaType.APPLICATION_JSON)
        .retrieve()
        .toEntity(ScenarioDetails.class)
        .timeout(HTTP_TIMEOUT)
        .block(HTTP_TIMEOUT);
    if (entity == null || entity.getBody() == null) {
      throw new IllegalStateException("Scenario Manager /scenarios/" + scenarioId + " returned no body");
    }
    return entity.getBody();
  }

  public record ScenarioSummary(String id, String name) {
  }

  public record ScenarioDetails(String id, String name, String description, SwarmTemplate template) {
  }
}
