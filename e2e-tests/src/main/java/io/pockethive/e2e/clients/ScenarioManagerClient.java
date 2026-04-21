package io.pockethive.e2e.clients;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
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
    return create(baseUrl, null);
  }

  public static ScenarioManagerClient create(URI baseUrl, String bearerToken) {
    Objects.requireNonNull(baseUrl, "baseUrl");
    WebClient.Builder builder = WebClient.builder().baseUrl(baseUrl.toString());
    if (bearerToken != null && !bearerToken.isBlank()) {
      builder.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + bearerToken);
    }
    return new ScenarioManagerClient(builder.build());
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

  public List<TemplateSummary> listTemplates() {
    ResponseEntity<List<TemplateSummary>> entity = webClient.get()
        .uri("/api/templates")
        .accept(MediaType.APPLICATION_JSON)
        .retrieve()
        .toEntity(new ParameterizedTypeReference<List<TemplateSummary>>() {})
        .timeout(HTTP_TIMEOUT)
        .block(HTTP_TIMEOUT);
    if (entity == null || entity.getBody() == null) {
      throw new IllegalStateException("Scenario Manager /api/templates returned no body");
    }
    return entity.getBody();
  }

  public record ScenarioSummary(String id, String name) {
  }

  public record ScenarioDetails(String id, String name, String description, SwarmTemplate template) {
  }

  public record TemplateSummary(String bundleKey,
                                String bundlePath,
                                String folderPath,
                                String id,
                                String name,
                                boolean defunct) {
  }
}
