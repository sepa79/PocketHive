package io.pockethive.e2e.clients;

import java.net.URI;
import java.time.Duration;
import java.util.Objects;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Minimal client for obtaining dev bearer tokens from the standalone auth-service.
 */
public final class AuthServiceClient {

  private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(10);

  private final WebClient webClient;

  private AuthServiceClient(WebClient webClient) {
    this.webClient = webClient;
  }

  public static AuthServiceClient create(URI baseUrl) {
    Objects.requireNonNull(baseUrl, "baseUrl");
    return new AuthServiceClient(WebClient.builder().baseUrl(baseUrl.toString()).build());
  }

  public String devLogin(String username) {
    Objects.requireNonNull(username, "username");
    ResponseEntity<SessionResponse> entity = webClient.post()
        .uri("/api/auth/dev/login")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(new DevLoginRequest(username))
        .retrieve()
        .toEntity(SessionResponse.class)
        .timeout(HTTP_TIMEOUT)
        .block(HTTP_TIMEOUT);
    if (entity == null || entity.getBody() == null || entity.getBody().accessToken() == null
        || entity.getBody().accessToken().isBlank()) {
      throw new IllegalStateException("auth-service dev login returned no access token for " + username);
    }
    return entity.getBody().accessToken();
  }

  private record DevLoginRequest(String username) {
  }

  private record SessionResponse(String accessToken) {
  }
}
