package io.pockethive.e2e.clients;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
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
    return create(baseUrl, null);
  }

  public static AuthServiceClient create(URI baseUrl, String bearerToken) {
    Objects.requireNonNull(baseUrl, "baseUrl");
    WebClient.Builder builder = WebClient.builder().baseUrl(baseUrl.toString());
    if (bearerToken != null && !bearerToken.isBlank()) {
      builder.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + bearerToken);
    }
    return new AuthServiceClient(builder.build());
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

  public AuthenticatedUser me() {
    ResponseEntity<AuthenticatedUser> entity = webClient.get()
        .uri("/api/auth/me")
        .accept(MediaType.APPLICATION_JSON)
        .retrieve()
        .toEntity(AuthenticatedUser.class)
        .timeout(HTTP_TIMEOUT)
        .block(HTTP_TIMEOUT);
    if (entity == null || entity.getBody() == null) {
      throw new IllegalStateException("auth-service /api/auth/me returned no body");
    }
    return entity.getBody();
  }

  public List<AuthenticatedUser> listUsers() {
    ResponseEntity<List<AuthenticatedUser>> entity = webClient.get()
        .uri("/api/auth/admin/users")
        .accept(MediaType.APPLICATION_JSON)
        .retrieve()
        .toEntity(new ParameterizedTypeReference<List<AuthenticatedUser>>() {})
        .timeout(HTTP_TIMEOUT)
        .block(HTTP_TIMEOUT);
    if (entity == null || entity.getBody() == null) {
      throw new IllegalStateException("auth-service /api/auth/admin/users returned no body");
    }
    return entity.getBody();
  }

  public AuthenticatedUser upsertUser(UUID userId, String username, String displayName, boolean active) {
    Objects.requireNonNull(userId, "userId");
    ResponseEntity<AuthenticatedUser> entity = webClient.put()
        .uri("/api/auth/admin/users/{userId}", userId)
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(new UserUpsertRequest(username, displayName, active))
        .retrieve()
        .toEntity(AuthenticatedUser.class)
        .timeout(HTTP_TIMEOUT)
        .block(HTTP_TIMEOUT);
    if (entity == null || entity.getBody() == null) {
      throw new IllegalStateException("auth-service upsert user returned no body for " + userId);
    }
    return entity.getBody();
  }

  public AuthenticatedUser replaceGrants(UUID userId, List<AuthGrant> grants) {
    Objects.requireNonNull(userId, "userId");
    ResponseEntity<AuthenticatedUser> entity = webClient.put()
        .uri("/api/auth/admin/users/{userId}/grants", userId)
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(new UserGrantsReplaceRequest(grants))
        .retrieve()
        .toEntity(AuthenticatedUser.class)
        .timeout(HTTP_TIMEOUT)
        .block(HTTP_TIMEOUT);
    if (entity == null || entity.getBody() == null) {
      throw new IllegalStateException("auth-service replace grants returned no body for " + userId);
    }
    return entity.getBody();
  }

  private record DevLoginRequest(String username) {
  }

  private record SessionResponse(String accessToken) {
  }

  private record UserUpsertRequest(String username, String displayName, boolean active) {
  }

  private record UserGrantsReplaceRequest(List<AuthGrant> grants) {
  }

  public record AuthenticatedUser(UUID id,
                                  String username,
                                  String displayName,
                                  boolean active,
                                  String authProvider,
                                  List<AuthGrant> grants) {
  }

  public record AuthGrant(String product, String permission, String resourceType, String resourceSelector) {
  }
}
