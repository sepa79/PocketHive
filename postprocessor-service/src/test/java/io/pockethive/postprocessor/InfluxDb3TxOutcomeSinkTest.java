package io.pockethive.postprocessor;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InfluxDb3TxOutcomeSinkTest {

  private HttpServer server;

  @AfterEach
  void tearDown() {
    if (server != null) {
      server.stop(0);
    }
  }

  @Test
  void writeFailsFastWhenRequiredConfigIsMissing() {
    InfluxDb3SinkProperties properties = new InfluxDb3SinkProperties();
    properties.setBatchSize(10);
    properties.setFlushIntervalMs(10_000);

    InfluxDb3TxOutcomeSink sink = new InfluxDb3TxOutcomeSink(properties);

    assertThatThrownBy(() -> sink.write(sampleEvent("trace-1", "call-a", 1, 1)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("endpoint/database/measurement");
  }

  @Test
  void writeBatchesLinesAndFlushesToInfluxWriteEndpoint() throws Exception {
    CountDownLatch latch = new CountDownLatch(2);
    List<CapturedRequest> requests = new CopyOnWriteArrayList<>();
    server = HttpServer.create(new InetSocketAddress(0), 0);
    server.createContext("/api/v3/write_lp", exchange -> capture(exchange, requests, latch));
    server.start();

    InfluxDb3SinkProperties properties = new InfluxDb3SinkProperties();
    properties.setEndpoint("http://127.0.0.1:" + server.getAddress().getPort());
    properties.setDatabase("pockethive");
    properties.setMeasurement("ph_tx_outcome");
    properties.setToken("test-token");
    properties.setBatchSize(2);
    properties.setFlushIntervalMs(60_000);
    properties.setMaxBufferedEvents(10);

    InfluxDb3TxOutcomeSink sink = new InfluxDb3TxOutcomeSink(properties);
    sink.write(sampleEvent("trace-1", "call-a", 1, 1));
    sink.write(sampleEvent("trace-2", "call-b", 0, 1));
    sink.write(sampleEvent("trace-3", "", 1, 0));
    sink.flush();

    assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(requests).hasSize(2);

    CapturedRequest first = requests.get(0);
    assertThat(first.method).isEqualTo("POST");
    assertThat(first.path).isEqualTo("/api/v3/write_lp");
    assertThat(first.query).isEqualTo("db=pockethive&precision=ms");
    assertThat(first.authorization).isEqualTo("Bearer test-token");
    assertThat(first.contentType).isEqualTo("text/plain; charset=utf-8");
    assertThat(first.body).contains("\n");
    assertThat(first.body).contains("processorSuccess=1i");
    assertThat(first.body).contains("businessSuccess=1i");
    assertThat(first.body).contains("processorStatusClass=2xx");
    assertThat(first.body).contains("callIdKey=call-a");
    assertThat(first.body).contains("businessCodeKey=approved");

    CapturedRequest second = requests.get(1);
    assertThat(second.body).contains("callIdKey=unknown");
    assertThat(second.body).contains("businessCodeKey=n/a");
    assertThat(second.body).contains("processorSuccess=1i");
    assertThat(second.body).contains("businessSuccess=0i");
    assertThat(second.body).contains("processorStatusClass=2xx");
  }

  @Test
  void writeFailsWhenBufferLimitIsExceeded() throws Exception {
    CountDownLatch latch = new CountDownLatch(1);
    List<CapturedRequest> requests = new CopyOnWriteArrayList<>();
    server = HttpServer.create(new InetSocketAddress(0), 0);
    server.createContext("/api/v3/write_lp", exchange -> capture(exchange, requests, latch));
    server.start();

    InfluxDb3SinkProperties properties = new InfluxDb3SinkProperties();
    properties.setEndpoint("http://127.0.0.1:" + server.getAddress().getPort());
    properties.setDatabase("pockethive");
    properties.setMeasurement("ph_tx_outcome");
    properties.setBatchSize(10);
    properties.setFlushIntervalMs(60_000);
    properties.setMaxBufferedEvents(1);

    InfluxDb3TxOutcomeSink sink = new InfluxDb3TxOutcomeSink(properties);
    sink.write(sampleEvent("trace-1", "call-a", 1, 1));

    assertThatThrownBy(() -> sink.write(sampleEvent("trace-2", "call-b", 0, 0)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("buffer is full");
    assertThat(latch.await(300, TimeUnit.MILLISECONDS)).isFalse();
    assertThat(requests).isEmpty();
  }

  private void capture(HttpExchange exchange, List<CapturedRequest> requests, CountDownLatch latch) throws IOException {
    byte[] response = new byte[0];
    String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    requests.add(new CapturedRequest(
        exchange.getRequestMethod(),
        exchange.getRequestURI().getPath(),
        exchange.getRequestURI().getQuery(),
        exchange.getRequestHeaders().getFirst("Authorization"),
        exchange.getRequestHeaders().getFirst("Content-Type"),
        body));
    exchange.sendResponseHeaders(204, -1);
    exchange.getResponseBody().write(response);
    exchange.close();
    latch.countDown();
  }

  private static TxOutcomeEvent sampleEvent(String traceId, String callId, int processorSuccess, int businessSuccess) {
    Map<String, String> dimensions = new LinkedHashMap<>();
    dimensions.put("channel", "api");
    dimensions.put("tenant", "blue");
    return new TxOutcomeEvent(
        "2026-03-01 12:00:00.123",
        "swarm-a",
        "postprocessor",
        "instance-a",
        traceId,
        callId,
        200,
        processorSuccess,
        123L,
        businessSuccess == 1 ? "approved" : "",
        businessSuccess,
        dimensions);
  }

  private record CapturedRequest(
      String method,
      String path,
      String query,
      String authorization,
      String contentType,
      String body
  ) {
  }
}
