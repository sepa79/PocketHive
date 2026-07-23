package io.pockethive.e2e.clients;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import com.fasterxml.jackson.databind.JsonNode;
import io.pockethive.swarm.model.lifecycle.ControlResponse;
import io.pockethive.swarm.model.lifecycle.ControlRequest;
import io.pockethive.swarm.model.lifecycle.SwarmCreateRequest;

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
    return create(baseUrl, null);
  }

  public static OrchestratorClient create(URI baseUrl, String bearerToken) {
    Objects.requireNonNull(baseUrl, "baseUrl");
    WebClient.Builder builder = WebClient.builder().baseUrl(baseUrl.toString());
    if (bearerToken != null && !bearerToken.isBlank()) {
      builder.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + bearerToken);
    }
    return new OrchestratorClient(builder.build());
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

  public ControlResponse updateComponentConfig(String role, String instance, ComponentConfigRequest request) {
    Objects.requireNonNull(role, "role");
    Objects.requireNonNull(instance, "instance");
    Objects.requireNonNull(request, "request");
    return postControlRequest(componentPath("/api/components/{role}/{instance}/config", role, instance),
        request, ControlResponse.class);
  }

  public Optional<SwarmView> findSwarm(String swarmId) {
    Objects.requireNonNull(swarmId, "swarmId");
    try {
      ResponseEntity<JsonNode> entity = webClient.get()
          .uri(path("/api/swarms/{swarmId}", swarmId))
          .accept(MediaType.APPLICATION_JSON)
          .retrieve()
          .toEntity(JsonNode.class)
          .timeout(HTTP_TIMEOUT)
          .block(HTTP_TIMEOUT);
      if (entity == null || entity.getBody() == null) {
        return Optional.empty();
      }
      return Optional.ofNullable(toSwarmView(entity.getBody()));
    } catch (WebClientResponseException.NotFound notFound) {
      return Optional.empty();
    }
  }

  private static SwarmView toSwarmView(JsonNode snapshot) {
    if (snapshot == null || snapshot.isMissingNode()) {
      return null;
    }
    return new SwarmView(
        textOrNull(snapshot, "id"),
        textOrNull(snapshot, "runtimeIntent"),
        textOrNull(snapshot, "workloadIntent"),
        textOrNull(snapshot, "controllerState"),
        textOrNull(snapshot, "workloadState"),
        textOrNull(snapshot, "health"),
        textOrNull(snapshot, "runtimeResourceState"),
        textOrNull(snapshot, "observedAt"),
        snapshot.path("observationStale").asBoolean(true));
  }

  private static String textOrNull(JsonNode node, String field) {
    if (node == null || field == null) {
      return null;
    }
    JsonNode value = node.get(field);
    if (value == null || value.isNull()) {
      return null;
    }
    String text = value.asText(null);
    return text == null || text.isBlank() ? null : text;
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

  private static String componentPath(String template, String role, String instance) {
    String resolved = template.replace("{role}", role);
    return resolved.replace("{instance}", instance);
  }

  public record SwarmView(
      String id,
      String runtimeIntent,
      String workloadIntent,
      String controllerState,
      String workloadState,
      String health,
      String runtimeResourceState,
      String observedAt,
      boolean observationStale) {
  }

  public record ComponentConfigRequest(String idempotencyKey,
                                       Map<String, Object> patch,
                                       String notes,
                                       String swarmId) {

    public ComponentConfigRequest {
      patch = patch == null || patch.isEmpty() ? Map.of() : Map.copyOf(patch);
    }

    public ComponentConfigRequest withPatch(Map<String, Object> newPatch) {
      Map<String, Object> safePatch = newPatch == null || newPatch.isEmpty()
          ? Map.of()
          : Map.copyOf(newPatch);
      if (safePatch.equals(patch)) {
        return this;
      }
      return new ComponentConfigRequest(idempotencyKey, safePatch, notes, swarmId);
    }

    public ComponentConfigRequest withSwarmId(String newSwarmId) {
      if (Objects.equals(swarmId, newSwarmId)) {
        return this;
      }
      return new ComponentConfigRequest(idempotencyKey, patch, notes, newSwarmId);
    }
  }
}
