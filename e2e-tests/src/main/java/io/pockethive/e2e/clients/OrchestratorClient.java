package io.pockethive.e2e.clients;

import java.net.URI;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

/**
 * Thin wrapper around {@link WebClient} to access the Orchestrator REST API.
 */
public final class OrchestratorClient {

  private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(30);

  private final WebClient webClient;

  private OrchestratorClient(WebClient webClient) {
    this.webClient = webClient;
  }

  public static OrchestratorClient create(URI baseUrl) {
    Objects.requireNonNull(baseUrl, "baseUrl");
    return new OrchestratorClient(WebClient.builder().baseUrl(baseUrl.toString()).build());
  }

  public WebClient webClient() {
    return webClient;
  }

  public ControlResponse createSwarm(String swarmId, SwarmCreateRequest request) {
    Objects.requireNonNull(swarmId, "swarmId");
    Objects.requireNonNull(request, "request");
    return postControlRequest(path("/api/swarms/{swarmId}/create", swarmId), request, ControlResponse.class);
  }

  public ControlResponse startSwarm(String swarmId, ControlRequest request) {
    Objects.requireNonNull(swarmId, "swarmId");
    Objects.requireNonNull(request, "request");
    return postControlRequest(path("/api/swarms/{swarmId}/start", swarmId), request, ControlResponse.class);
  }

  public ControlResponse stopSwarm(String swarmId, ControlRequest request) {
    Objects.requireNonNull(swarmId, "swarmId");
    Objects.requireNonNull(request, "request");
    return postControlRequest(path("/api/swarms/{swarmId}/stop", swarmId), request, ControlResponse.class);
  }

  public ControlResponse removeSwarm(String swarmId, ControlRequest request) {
    Objects.requireNonNull(swarmId, "swarmId");
    Objects.requireNonNull(request, "request");
    return postControlRequest(path("/api/swarms/{swarmId}/remove", swarmId), request, ControlResponse.class);
  }

  public Optional<SwarmView> findSwarm(String swarmId) {
    Objects.requireNonNull(swarmId, "swarmId");
    try {
      ResponseEntity<SwarmView> entity = webClient.get()
          .uri(path("/api/swarms/{swarmId}", swarmId))
          .accept(MediaType.APPLICATION_JSON)
          .retrieve()
          .toEntity(SwarmView.class)
          .timeout(HTTP_TIMEOUT)
          .block(HTTP_TIMEOUT);
      return Optional.ofNullable(entity.getBody());
    } catch (WebClientResponseException.NotFound notFound) {
      return Optional.empty();
    }
  }

  private <T> T postControlRequest(String path, Object body, Class<T> responseType) {
    ResponseEntity<T> entity = webClient.post()
        .uri(path)
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(body)
        .retrieve()
        .toEntity(responseType)
        .timeout(HTTP_TIMEOUT)
        .block(HTTP_TIMEOUT);
    if (entity == null || entity.getBody() == null) {
      throw new IllegalStateException("No response received from orchestrator at " + path);
    }
    return entity.getBody();
  }

  private static String path(String template, String swarmId) {
    return template.replace("{swarmId}", swarmId);
  }

  public record ControlResponse(String correlationId, String idempotencyKey, Watch watch, long timeoutMs) {

    public Watch watch() {
      return watch == null ? new Watch(null, null) : watch;
    }
  }

  public record Watch(String successTopic, String errorTopic) {
  }

  public record ControlRequest(String idempotencyKey, String notes) {
  }

  public record SwarmCreateRequest(String templateId, String idempotencyKey, String notes) {
  }

  public record SwarmView(String id,
                          String status,
                          String health,
                          String heartbeat,
                          boolean workEnabled,
                          boolean controllerEnabled) {
  }
}
