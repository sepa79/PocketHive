package io.pockethive.e2e.clients;

import java.net.URI;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import io.pockethive.swarm.model.NetworkBinding;

/**
 * Thin wrapper around the network-proxy-manager REST API for acceptance checks.
 */
public final class NetworkProxyManagerClient {

  private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(20);

  private final WebClient webClient;

  private NetworkProxyManagerClient(WebClient webClient) {
    this.webClient = webClient;
  }

  public static NetworkProxyManagerClient create(URI baseUrl) {
    return create(baseUrl, null);
  }

  public static NetworkProxyManagerClient create(URI baseUrl, String bearerToken) {
    Objects.requireNonNull(baseUrl, "baseUrl");
    WebClient.Builder builder = WebClient.builder().baseUrl(baseUrl.toString());
    if (bearerToken != null && !bearerToken.isBlank()) {
      builder.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + bearerToken);
    }
    return new NetworkProxyManagerClient(builder.build());
  }

  public Optional<NetworkBinding> findBinding(String swarmId) {
    Objects.requireNonNull(swarmId, "swarmId");
    try {
      ResponseEntity<NetworkBinding> entity = webClient.get()
          .uri("/api/network/bindings/{swarmId}", swarmId)
          .accept(MediaType.APPLICATION_JSON)
          .retrieve()
          .toEntity(NetworkBinding.class)
          .timeout(HTTP_TIMEOUT)
          .block(HTTP_TIMEOUT);
      if (entity == null || entity.getBody() == null) {
        return Optional.empty();
      }
      return Optional.of(entity.getBody());
    } catch (WebClientResponseException.NotFound notFound) {
      return Optional.empty();
    }
  }
}
