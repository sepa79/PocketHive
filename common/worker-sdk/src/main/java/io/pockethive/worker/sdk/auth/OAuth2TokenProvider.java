package io.pockethive.worker.sdk.auth;

import static io.pockethive.worker.sdk.auth.AuthProfileFields.*;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.work.api.WorkerContext;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Responsibility: acquire ordinary OAuth credentials and coordinate its refresh policy through
 * TokenStore. Must not: sign OAuth requests, load profiles or implement storage claim arbitration.
 * Contract: RESP-WORK-OAUTH-TOKENS —
 * docs/architecture/runtime-responsibilities.md#resp-work-oauth-tokens.
 */
final class OAuth2TokenProvider {
  private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
  private static final Duration CLEANUP_GRACE = Duration.ofMinutes(5);
  private final TokenStore tokenStore;
  private final HttpClient httpClient;

  OAuth2TokenProvider(TokenStore tokenStore, HttpClient httpClient) {
    this.tokenStore = tokenStore;
    this.httpClient = httpClient;
  }

  AuthMaterial material(
      String profileId, String fingerprint, AuthProfile profile, WorkerContext context) {
    if (tokenStore == null) {
      throw new IllegalStateException(
          "Refreshable auth profile requires Redis token store: " + profileId);
    }
    String tokenKey = tokenKey(profile);
    Instant now = Instant.now();
    TokenRecord existing = tokenStore.get(tokenKey, fingerprint);
    if (existing != null && !existing.expired(now) && !existing.needsRefresh(now)) {
      return new AuthMaterial(
          existing.accessToken(), existing.tokenType(), existing.expiresAt(), existing.refreshAt());
    }
    RefreshClaim claim =
        new RefreshClaim(
            tokenKey,
            fingerprint,
            context.info().instanceId() + ":" + UUID.randomUUID(),
            now.plusSeconds(profile.getRefresh().getLeaseSeconds()));
    ClaimResult claimResult =
        tokenStore.claimRefresh(
            tokenKey,
            fingerprint,
            claim,
            Duration.ofSeconds(profile.getRefresh().getLeaseSeconds()));
    if (claimResult == ClaimResult.FINGERPRINT_MISMATCH) {
      throw new IllegalStateException("Auth token fingerprint mismatch for tokenKey=" + tokenKey);
    }
    if (claimResult == ClaimResult.OWNED_BY_OTHER && existing != null && !existing.expired(now)) {
      context
          .meterRegistry()
          .counter("pockethive.auth.refresh.lease_contention", "profileId", profileId)
          .increment();
      return new AuthMaterial(
          existing.accessToken(), existing.tokenType(), existing.expiresAt(), existing.refreshAt());
    }
    if (claimResult != ClaimResult.CLAIMED) {
      throw new IllegalStateException(
          "Unable to claim auth token refresh for tokenKey=" + tokenKey + ": " + claimResult);
    }
    try {
      TokenRecord refreshed = refreshOAuth(tokenKey, fingerprint, profile);
      tokenStore.store(refreshed, claim, CLEANUP_GRACE);
      context
          .meterRegistry()
          .counter("pockethive.auth.refresh", "profileId", profileId, "result", "success")
          .increment();
      return new AuthMaterial(
          refreshed.accessToken(),
          refreshed.tokenType(),
          refreshed.expiresAt(),
          refreshed.refreshAt());
    } catch (RuntimeException ex) {
      tokenStore.releaseClaim(tokenKey, fingerprint, claim);
      context
          .meterRegistry()
          .counter("pockethive.auth.refresh", "profileId", profileId, "result", "failure")
          .increment();
      throw ex;
    }
  }

  private TokenRecord refreshOAuth(String tokenKey, String fingerprint, AuthProfile profile) {
    try {
      Map<String, String> form = new LinkedHashMap<>();
      if (profile.getType() == AuthType.OAUTH2_CLIENT_CREDENTIALS) {
        form.put("grant_type", "client_credentials");
        form.put("client_id", required(profile, "clientId"));
        form.put("client_secret", required(profile, "clientSecret"));
      } else if (profile.getType() == AuthType.OAUTH2_PASSWORD_GRANT) {
        form.put("grant_type", "password");
        form.put("username", required(profile, "username"));
        form.put("password", required(profile, "password"));
        String clientId = optional(profile, "clientId");
        if (clientId != null) {
          form.put("client_id", clientId);
        }
      } else {
        throw new IllegalArgumentException("Auth type is not refreshable: " + profile.getType());
      }
      String scope = optional(profile, "scope");
      if (scope != null) {
        form.put("scope", scope);
      }
      String body = formEncode(form);
      HttpRequest request =
          HttpRequest.newBuilder(URI.create(required(profile, "tokenUrl")))
              .timeout(Duration.ofSeconds(15))
              .header("Content-Type", "application/x-www-form-urlencoded")
              .POST(HttpRequest.BodyPublishers.ofString(body))
              .build();
      HttpResponse<String> response =
          httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() < 200 || response.statusCode() >= 300) {
        throw new IllegalStateException("OAuth token endpoint returned " + response.statusCode());
      }
      Map<String, Object> json = JSON.readValue(response.body(), new TypeReference<>() {});
      Object tokenObj = json.get("access_token");
      if (tokenObj == null || tokenObj.toString().isBlank()) {
        throw new IllegalStateException("OAuth token response missing access_token");
      }
      String tokenType = json.getOrDefault("token_type", "Bearer").toString();
      int expiresIn = json.get("expires_in") instanceof Number n ? n.intValue() : 3600;
      Instant now = Instant.now();
      Instant expiresAt = now.plusSeconds(Math.max(1, expiresIn));
      Instant refreshAt = expiresAt.minusSeconds(profile.getRefresh().getRefreshAheadSeconds());
      return new TokenRecord(
          tokenKey, fingerprint, tokenObj.toString(), tokenType, expiresAt, refreshAt);
    } catch (RuntimeException ex) {
      throw ex;
    } catch (Exception ex) {
      throw new IllegalStateException("OAuth token refresh failed", ex);
    }
  }

  private static String formEncode(Map<String, String> form) {
    return form.entrySet().stream()
        .map(e -> url(e.getKey()) + "=" + url(e.getValue()))
        .reduce((a, b) -> a + "&" + b)
        .orElse("");
  }

  private static String url(String value) {
    return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
  }
}
