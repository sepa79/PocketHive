package io.pockethive.worker.sdk.auth;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.pockethive.work.api.WorkerContext;
import io.pockethive.work.api.WorkerInfo;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;

class OAuth2TokenProviderTest {
  @ParameterizedTest
  @EnumSource(
      value = AuthType.class,
      names = {"OAUTH2_CLIENT_CREDENTIALS", "OAUTH2_PASSWORD_GRANT"})
  void ordinaryGrantUsesItsWireContractAndPublishesOnlyAfterAcquisition(AuthType type)
      throws Exception {
    var request = new CompletableFuture<Map<String, String>>();
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/token",
        exchange -> {
          request.complete(
              Map.of(
                  "body",
                  new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8),
                  "contentType",
                  exchange.getRequestHeaders().getFirst("Content-Type")));
          byte[] body =
              "{\"access_token\":\"ordinary-test-token\",\"token_type\":\"Bearer\",\"expires_in\":3600}"
                  .getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    server.start();
    try (var http = HttpClient.newHttpClient()) {
      var profile = profile(type);
      profile.putProperty(
          "tokenUrl", "http://127.0.0.1:" + server.getAddress().getPort() + "/token");
      var store = claimedStore();
      var material =
          new OAuth2TokenProvider(store, http)
              .material("ordinary", "fingerprint", profile, context());
      assertThat(material.value()).isEqualTo("ordinary-test-token");
      assertThat(request.get().get("contentType")).isEqualTo("application/x-www-form-urlencoded");
      assertThat(request.get().get("body"))
          .isEqualTo(
              type == AuthType.OAUTH2_CLIENT_CREDENTIALS
                  ? "grant_type=client_credentials&client_id=client%2B&client_secret=secret%26&scope=read+write"
                  : "grant_type=password&username=user%2B&password=password%26&client_id=client%2B&scope=read+write");
      var record = ArgumentCaptor.forClass(TokenRecord.class);
      verify(store).store(record.capture(), any(), any());
      assertThat(record.getValue().accessToken()).isEqualTo(material.value());
      assertThat(record.getValue().fingerprint()).isEqualTo("fingerprint");
      verify(store, never()).releaseClaim(any(), any(), any());
    } finally {
      server.stop(0);
    }
  }

  @Test
  void refreshContentionMayUseAnUnexpiredTokenButNeverAnExpiredToken() {
    var store = mock(TokenStore.class);
    var http = mock(HttpClient.class);
    var now = Instant.now();
    when(store.get(any(), any()))
        .thenReturn(
            new TokenRecord(
                "key", "fingerprint", "warm", "Bearer", now.plusSeconds(120), now.minusSeconds(1)));
    when(store.claimRefresh(any(), any(), any(), any())).thenReturn(ClaimResult.OWNED_BY_OTHER);
    var provider = new OAuth2TokenProvider(store, http);
    assertThat(
            provider
                .material(
                    "ordinary",
                    "fingerprint",
                    profile(AuthType.OAUTH2_CLIENT_CREDENTIALS),
                    context())
                .value())
        .isEqualTo("warm");
    when(store.get(any(), any()))
        .thenReturn(
            new TokenRecord(
                "key",
                "fingerprint",
                "expired",
                "Bearer",
                now.minusSeconds(1),
                now.minusSeconds(10)));
    assertThatThrownBy(
            () ->
                provider.material(
                    "ordinary",
                    "fingerprint",
                    profile(AuthType.OAUTH2_CLIENT_CREDENTIALS),
                    context()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Unable to claim");
    verifyNoInteractions(http);
    verify(store, never()).store(any(), any(), any());
  }

  @Test
  void malformedTokenResponseReleasesClaimAndCannotPublishCredential() throws Exception {
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/token",
        exchange -> {
          exchange.getRequestBody().readAllBytes();
          byte[] body = "{\"expires_in\":3600}".getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    server.start();
    try (var http = HttpClient.newHttpClient()) {
      var profile = profile(AuthType.OAUTH2_CLIENT_CREDENTIALS);
      profile.putProperty(
          "tokenUrl", "http://127.0.0.1:" + server.getAddress().getPort() + "/token");
      var store = claimedStore();
      assertThatThrownBy(
              () ->
                  new OAuth2TokenProvider(store, http)
                      .material("ordinary", "fingerprint", profile, context()))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("missing access_token");
      verify(store).releaseClaim(eq("key"), eq("fingerprint"), any());
      verify(store, never()).store(any(), any(), any());
    } finally {
      server.stop(0);
    }
  }

  private TokenStore claimedStore() {
    var store = mock(TokenStore.class);
    when(store.claimRefresh(any(), any(), any(), any())).thenReturn(ClaimResult.CLAIMED);
    return store;
  }

  private WorkerContext context() {
    var context = mock(WorkerContext.class);
    when(context.info()).thenReturn(new WorkerInfo("worker", "swarm", "worker-1", null, null));
    when(context.meterRegistry()).thenReturn(new SimpleMeterRegistry());
    return context;
  }

  private AuthProfile profile(AuthType type) {
    var profile = new AuthProfile();
    profile.setType(type);
    profile.getStorage().setMode(AuthStorageMode.REDIS);
    profile.getStorage().setTokenKey("key");
    profile.putProperty("clientId", "client+");
    profile.putProperty("clientSecret", "secret&");
    profile.putProperty("username", "user+");
    profile.putProperty("password", "password&");
    profile.putProperty("scope", "read write");
    return profile;
  }
}
