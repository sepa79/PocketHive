package io.pockethive.acceptance.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Responsibility: bound each complete HTTP exchange, including its body, within the selected ingress.
 * Must not: retry commands, follow redirects or determine operation success.
 * Contract: RESP-ACCEPTANCE-HTTP — docs/architecture/acceptance-tests.md#resp-acceptance-http.
 */
public final class PocketHiveHttp implements AutoCloseable {
  private final URI ingress;
  private final Duration requestTimeout;
  private final HttpClient client;
  private final ObjectMapper json = JsonMapper.builder().findAndAddModules()
      .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).build();

  public PocketHiveHttp(URI ingress, Duration requestTimeout) {
    this.ingress = Objects.requireNonNull(ingress);
    this.requestTimeout = Objects.requireNonNull(requestTimeout);
    this.client = HttpClient.newBuilder().connectTimeout(requestTimeout)
        .followRedirects(HttpClient.Redirect.NEVER).build();
  }

  public ApiResponse request(String method, String path, Object body, String token)
      throws IOException, InterruptedException {
    return request(method, path, body, token, requestTimeout);
  }

  public ApiResponse request(String method, String path, Object body, String token, Duration budget)
      throws IOException, InterruptedException {
    return request(method, path, body, token, budget, "application/json");
  }

  public ApiResponse request(String method, String path, Object body, String token, String accept)
      throws IOException, InterruptedException {
    return request(method, path, body, token, requestTimeout, accept);
  }

  private ApiResponse request(String method, String path, Object body, String token, Duration budget, String accept)
      throws IOException, InterruptedException {
    return exchange(method, path, body == null ? null : json.writeValueAsBytes(body), bearer(token), budget, accept, "application/json");
  }

  public ApiResponse requestText(String method, String path, String body, String token)
      throws IOException, InterruptedException {
    return exchange(method, path, body.getBytes(StandardCharsets.UTF_8), bearer(token), requestTimeout, "text/plain", "text/plain");
  }

  public ApiResponse getWithBasicAuth(String path, String username, String password)
      throws IOException, InterruptedException {
    return requestWithBasicAuth("GET", path, null, username, password, requestTimeout);
  }

  public ApiResponse requestWithBasicAuth(String method, String path, Object body, String username,
      String password, Duration budget) throws IOException, InterruptedException {
    String credentials = java.util.Base64.getEncoder().encodeToString(
        (username + ":" + password).getBytes(StandardCharsets.UTF_8));
    return exchange(method, path, body == null ? null : json.writeValueAsBytes(body), "Basic " + credentials,
        budget, "application/json", "application/json");
  }

  private static String bearer(String token) { return token.isEmpty() ? "" : "Bearer " + token; }

  private ApiResponse exchange(String method, String path, byte[] body, String authorization, Duration budget,
                               String accept, String contentType) throws IOException, InterruptedException {
    URI destination = ingress.resolve(path);
    if (!Objects.equals(ingress.getScheme(), destination.getScheme())
        || !Objects.equals(ingress.getRawAuthority(), destination.getRawAuthority())
        || destination.getFragment() != null) {
      throw new IllegalArgumentException("API link is outside the selected ingress: " + path);
    }
    Duration timeout = budget.compareTo(requestTimeout) < 0 ? budget : requestTimeout;
    if (timeout.isNegative() || timeout.isZero()) throw new IllegalArgumentException("HTTP budget exhausted");
    var request = HttpRequest.newBuilder(destination).timeout(timeout).header("Accept", accept);
    if (!authorization.isEmpty()) request.header("Authorization", authorization);
    var publisher = HttpRequest.BodyPublishers.noBody();
    if (body != null) {
      request.header("Content-Type", contentType);
      publisher = HttpRequest.BodyPublishers.ofByteArray(body);
    }
    var exchange = client.sendAsync(request.method(method, publisher).build(), HttpResponse.BodyHandlers.ofString());
    try {
      var response = exchange.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
      return new ApiResponse(method, destination.getRawPath(), response.statusCode(), response.body());
    } catch (TimeoutException failure) {
      exchange.cancel(true);
      throw new HttpTimeoutException(method + " " + destination.getRawPath() + " exceeded " + timeout);
    } catch (InterruptedException failure) {
      exchange.cancel(true);
      throw failure;
    } catch (ExecutionException failure) {
      Throwable cause = failure.getCause();
      if (cause instanceof IOException io) throw io;
      if (cause instanceof RuntimeException runtime) throw runtime;
      if (cause instanceof Error error) throw error;
      throw new IOException("HTTP exchange failed", cause);
    }
  }

  public <T> T decode(ApiResponse response, Class<T> type) throws IOException {
    return json.readValue(response.body(), type);
  }

  public <T> T decode(JsonNode node, Class<T> type) throws IOException {
    return json.treeToValue(node, type);
  }

  public JsonNode tree(ApiResponse response) throws IOException {
    return json.readTree(response.body());
  }

  @Override public void close() { client.close(); }
}
