package io.pockethive.sink.clickhouse.metrics;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ClickHouseMetricsSinkTest {

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  @Test
  void writesJsonEachRowToConfiguredTable() throws Exception {
    try (TestClickHouseServer server = TestClickHouseServer.start()) {
      ClickHouseMetricsSinkProperties properties = properties(server.endpoint());
      properties.setBatchSize(1);
      properties.setUsername("pockethive");
      properties.setPassword("secret");
      ClickHouseMetricsSink sink = new ClickHouseMetricsSink(properties, OBJECT_MAPPER);

      sink.write(sample(Map.of("queue", "moderator-a-out")));

      assertThat(server.requests()).hasSize(1);
      Request request = server.requests().getFirst();
      assertThat(decodedQuery(request)).isEqualTo(
          "INSERT INTO " + ClickHouseMetricsSinkProperties.DEFAULT_TABLE + " FORMAT JSONEachRow");
      String expectedAuth = "Basic " + Base64.getEncoder()
          .encodeToString("pockethive:secret".getBytes(StandardCharsets.UTF_8));
      assertThat(request.authorization()).isEqualTo(expectedAuth);

      JsonNode row = OBJECT_MAPPER.readTree(request.body().strip());
      assertThat(row.get("eventTime").asText()).isEqualTo("2026-07-03 10:15:30.123");
      assertThat(row.get("swarmId").asText()).isEqualTo("swarm-a");
      assertThat(row.get("runId").asText()).isEqualTo(ClickHouseMetricSample.NOT_RUN_SCOPED);
      assertThat(row.get("role").asText()).isEqualTo("processor");
      assertThat(row.get("instance").asText()).isEqualTo("processor-1");
      assertThat(row.get("metricName").asText()).isEqualTo("ph_test_total");
      assertThat(row.get("metricKind").asText()).isEqualTo(ClickHouseMetricKind.COUNTER.name());
      assertThat(row.get("statistic").asText()).isEqualTo(ClickHouseMetricStatistic.VALUE.name());
      assertThat(row.get("value").asDouble()).isEqualTo(7.0);
      assertThat(row.get("unit").asText()).isEqualTo("count");
      assertThat(row.get("labels").get("queue").asText()).isEqualTo("moderator-a-out");
    }
  }

  @Test
  void rejectsUnconfiguredSink() {
    ClickHouseMetricsSink sink = new ClickHouseMetricsSink(new ClickHouseMetricsSinkProperties(), OBJECT_MAPPER);

    assertThatThrownBy(() -> sink.write(sample(Map.of())))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("endpoint/table");
  }

  @Test
  void rejectsInvalidTableName() throws Exception {
    try (TestClickHouseServer server = TestClickHouseServer.start()) {
      ClickHouseMetricsSinkProperties properties = properties(server.endpoint());

      assertThatThrownBy(() -> properties.setTable("ph_metrics_samples;DROP"))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("table name");
      assertThat(server.requests()).isEmpty();
    }
  }

  @Test
  void rejectsLabelsOutsideConfiguredBounds() throws Exception {
    try (TestClickHouseServer server = TestClickHouseServer.start()) {
      ClickHouseMetricsSinkProperties properties = properties(server.endpoint());
      properties.setMaxLabelCount(1);
      ClickHouseMetricsSink sink = new ClickHouseMetricsSink(properties, OBJECT_MAPPER);

      assertThatThrownBy(() -> sink.write(sample(Map.of("one", "1", "two", "2"))))
          .isInstanceOf(ClickHouseMetricSampleRejectedException.class)
          .hasMessageContaining("maxLabelCount");
      assertThat(sink.bufferedSamples()).isZero();
      assertThat(server.requests()).isEmpty();
    }
  }

  @Test
  void failsExplicitlyWhenBufferIsFull() throws Exception {
    try (TestClickHouseServer server = TestClickHouseServer.start()) {
      ClickHouseMetricsSinkProperties properties = properties(server.endpoint());
      properties.setBatchSize(10);
      properties.setFlushIntervalMs(60_000);
      properties.setMaxBufferedSamples(1);
      ClickHouseMetricsSink sink = new ClickHouseMetricsSink(properties, OBJECT_MAPPER);

      sink.write(sample(Map.of()));

      assertThatThrownBy(() -> sink.write(sample(Map.of("attempt", "second"))))
          .isInstanceOf(ClickHouseMetricsBufferFullException.class)
          .hasMessageContaining("maxBufferedSamples=1");
      assertThat(sink.bufferedSamples()).isEqualTo(1);
      assertThat(server.requests()).isEmpty();
    }
  }

  @Test
  void requeuesFailedInsertForNextFlush() throws Exception {
    try (TestClickHouseServer server = TestClickHouseServer.start()) {
      server.enqueueResponse(500, "insert failed");
      server.enqueueResponse(200, "ok");
      ClickHouseMetricsSinkProperties properties = properties(server.endpoint());
      properties.setBatchSize(1);
      ClickHouseMetricsSink sink = new ClickHouseMetricsSink(properties, OBJECT_MAPPER);

      assertThatThrownBy(() -> sink.write(sample(Map.of("phase", "first"))))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("status=500");
      assertThat(sink.bufferedSamples()).isEqualTo(1);

      sink.flush();

      assertThat(sink.bufferedSamples()).isZero();
      assertThat(server.requests()).hasSize(2);
      assertThat(server.requests().get(1).body()).isEqualTo(server.requests().getFirst().body());
    }
  }

  private static ClickHouseMetricsSinkProperties properties(String endpoint) {
    ClickHouseMetricsSinkProperties properties = new ClickHouseMetricsSinkProperties();
    properties.setEndpoint(endpoint);
    properties.setTable(ClickHouseMetricsSinkProperties.DEFAULT_TABLE);
    properties.setConnectTimeoutMs(1000);
    properties.setReadTimeoutMs(1000);
    properties.setFlushIntervalMs(60_000);
    return properties;
  }

  private static ClickHouseMetricSample sample(Map<String, String> labels) {
    return new ClickHouseMetricSample(
        Instant.parse("2026-07-03T10:15:30.123Z"),
        "swarm-a",
        ClickHouseMetricSample.NOT_RUN_SCOPED,
        "processor",
        "processor-1",
        "ph_test_total",
        ClickHouseMetricKind.COUNTER,
        ClickHouseMetricStatistic.VALUE,
        7.0,
        "count",
        labels);
  }

  private static String decodedQuery(Request request) {
    String rawQuery = request.rawQuery();
    assertThat(rawQuery).startsWith("query=");
    return URLDecoder.decode(rawQuery.substring("query=".length()), StandardCharsets.UTF_8);
  }

  private record Request(String rawQuery, String body, String authorization) {
  }

  private record Response(int status, String body) {
  }

  private static final class TestClickHouseServer implements AutoCloseable {
    private final HttpServer server;
    private final List<Request> requests = new ArrayList<>();
    private final ConcurrentLinkedQueue<Response> responses = new ConcurrentLinkedQueue<>();

    private TestClickHouseServer(HttpServer server) {
      this.server = server;
    }

    static TestClickHouseServer start() throws IOException {
      HttpServer httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      TestClickHouseServer wrapper = new TestClickHouseServer(httpServer);
      httpServer.createContext("/", wrapper::handle);
      httpServer.start();
      return wrapper;
    }

    String endpoint() {
      return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    List<Request> requests() {
      return requests;
    }

    void enqueueResponse(int status, String body) {
      responses.add(new Response(status, body));
    }

    @Override
    public void close() {
      server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
      String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
      requests.add(new Request(
          exchange.getRequestURI().getRawQuery(),
          body,
          exchange.getRequestHeaders().getFirst("Authorization")));
      Response response = responses.poll();
      if (response == null) {
        response = new Response(200, "ok");
      }
      byte[] responseBytes = response.body().getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(response.status(), responseBytes.length);
      exchange.getResponseBody().write(responseBytes);
      exchange.close();
    }
  }
}
