package io.pockethive.sink.clickhouse;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Objects;

/**
 * Responsibility: own ClickHouse JSONEachRow HTTP inserts, auth and success checking.
 * Must not: construct domain rows, own queues or introduce retries/configuration defaults.
 * Contract: RESP-CLICKHOUSE-INSERT — docs/architecture/runtime-responsibilities.md#resp-clickhouse-insert.
 */
public final class ClickHouseJsonEachRowTransport {
  private static final String INSERT_QUERY_TEMPLATE = "INSERT INTO %s FORMAT JSONEachRow";
  private static final String CONTENT_TYPE = "Content-Type";
  private static final String JSON_CONTENT_TYPE = "application/json";
  private static final String AUTHORIZATION = "Authorization";
  private static final String BASIC_AUTH = "Basic ";

  private final ClickHouseConnectionSettings settings;
  private final HttpClient client;

  public ClickHouseJsonEachRowTransport(ClickHouseConnectionSettings settings) {
    this(Objects.requireNonNull(settings, "settings"), HttpClient.newBuilder()
        .connectTimeout(Duration.ofMillis(settings.getConnectTimeoutMs())).build());
  }

  ClickHouseJsonEachRowTransport(ClickHouseConnectionSettings settings, HttpClient client) {
    this.settings = Objects.requireNonNull(settings, "settings");
    this.client = Objects.requireNonNull(client, "client");
  }

  public ClickHouseInsert prepareInsert() {
    String endpoint = trim(settings.getEndpoint());
    String base = endpoint.endsWith("/") ? endpoint.substring(0, endpoint.length() - 1) : endpoint;
    String query = INSERT_QUERY_TEMPLATE.formatted(trim(settings.getTable()));
    URI uri = URI.create(base + "/?query=" + URLEncoder.encode(query, StandardCharsets.UTF_8));
    return rows -> insert(uri, rows);
  }

  private void insert(URI uri, List<String> rows) throws Exception {
    StringBuilder payload = new StringBuilder(rows.size() * 256);
    for (String row : rows) {
      payload.append(row).append('\n');
    }
    HttpRequest.Builder request = HttpRequest.newBuilder(uri)
        .timeout(Duration.ofMillis(settings.getReadTimeoutMs()))
        .header(CONTENT_TYPE, JSON_CONTENT_TYPE)
        .POST(HttpRequest.BodyPublishers.ofString(payload.toString(), StandardCharsets.UTF_8));
    String username = trim(settings.getUsername());
    if (!username.isEmpty()) {
      String auth = username + ":" + trim(settings.getPassword());
      request.header(AUTHORIZATION, BASIC_AUTH + Base64.getEncoder()
          .encodeToString(auth.getBytes(StandardCharsets.UTF_8)));
    }
    HttpResponse<String> response = client.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    if (response.statusCode() / 100 != 2) {
      throw new ClickHouseInsertException(response.statusCode(), response.body());
    }
  }

  private static String trim(String value) {
    return value == null ? "" : value.trim();
  }
}
