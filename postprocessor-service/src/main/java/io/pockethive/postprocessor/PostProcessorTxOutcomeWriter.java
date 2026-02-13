package io.pockethive.postprocessor;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.sink.clickhouse.ClickHouseSinkProperties;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
class PostProcessorTxOutcomeWriter {

  private final ClickHouseSinkProperties properties;
  private final ObjectMapper objectMapper;
  private final HttpClient client;

  PostProcessorTxOutcomeWriter(ClickHouseSinkProperties properties, ObjectMapper objectMapper) {
    this.properties = Objects.requireNonNull(properties, "properties");
    this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    this.client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofMillis(properties.getConnectTimeoutMs()))
        .build();
  }

  void write(TxOutcomeEvent event) throws Exception {
    if (!properties.configured()) {
      throw new IllegalStateException("ClickHouse sink is enabled but endpoint/table is not configured");
    }
    URI uri = insertUri();
    String payload = objectMapper.writeValueAsString(event) + "\n";
    HttpRequest.Builder request = HttpRequest.newBuilder(uri)
        .timeout(Duration.ofMillis(properties.getReadTimeoutMs()))
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8));
    String username = trim(properties.getUsername());
    if (!username.isEmpty()) {
      String password = trim(properties.getPassword());
      String auth = username + ":" + password;
      String encoded = Base64.getEncoder().encodeToString(auth.getBytes(StandardCharsets.UTF_8));
      request.header("Authorization", "Basic " + encoded);
    }
    HttpResponse<String> response = client.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    if (response.statusCode() / 100 != 2) {
      String body = response.body() == null ? "" : response.body().trim();
      if (body.length() > 500) {
        body = body.substring(0, 500) + "…";
      }
      throw new IllegalStateException("ClickHouse insert failed status=" + response.statusCode() + " body=" + body);
    }
  }

  private URI insertUri() {
    String endpoint = trim(properties.getEndpoint());
    String base = endpoint.endsWith("/") ? endpoint.substring(0, endpoint.length() - 1) : endpoint;
    String query = "INSERT INTO " + trim(properties.getTable()) + " FORMAT JSONEachRow";
    String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8);
    return URI.create(base + "/?query=" + encoded);
  }

  private static String trim(String value) {
    return value == null ? "" : value.trim();
  }
}
