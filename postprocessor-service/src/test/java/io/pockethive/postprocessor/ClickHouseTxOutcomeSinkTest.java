package io.pockethive.postprocessor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.pockethive.sink.clickhouse.ClickHouseSinkProperties;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ClickHouseTxOutcomeSinkTest {
  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Test
  void flushesFirstWriteImmediatelyThenBuffersUntilShutdown() throws Exception {
    try (var server = new Server()) {
      var sink = new ClickHouseTxOutcomeSink(server.settings, MAPPER);
      sink.write(event("first"));
      sink.write(event("second"));
      assertThat(server.bodies).containsExactly(json("first"));
      sink.flushOnShutdown();
      assertThat(server.bodies).containsExactly(json("first"), json("second"));
      assertThat(server.queries).allMatch(q -> q.equals("query=INSERT+INTO+db.events+FORMAT+JSONEachRow"));
      assertThat(server.auth).containsExactly("Basic dXNlcjpwYXNz", "Basic dXNlcjpwYXNz");
    }
  }

  @Test
  void rejectsFullBufferWithoutLosingAlreadyAcceptedEvents() throws Exception {
    try (var server = new Server()) {
      server.settings.setMaxBufferedEvents(1);
      var sink = new ClickHouseTxOutcomeSink(server.settings, MAPPER);
      sink.write(event("prime"));
      sink.write(event("accepted"));
      assertThatThrownBy(() -> sink.write(event("rejected")))
          .isInstanceOf(ClickHouseTxOutcomeBufferFullException.class)
          .hasMessageContaining("maxBufferedEvents=1");
      sink.flush();
      assertThat(server.bodies).containsExactly(json("prime"), json("accepted"));
    }
  }

  @Test
  void failedFlushRequeuesOnlyRejectedBatchAndDoesNotRepeatCommittedBatch() throws Exception {
    try (var server = new Server()) {
      var sink = new ClickHouseTxOutcomeSink(server.settings, MAPPER);
      sink.write(event("prime"));
      for (String id : List.of("a", "b", "c", "d")) sink.write(event(id));
      server.settings.setBatchSize(2);
      server.responses.addAll(List.of(200, 500, 200));
      assertThatThrownBy(sink::flush)
          .hasMessage("ClickHouse insert failed status=500 body=failed");
      sink.flush();
      sink.flush();
      assertThat(server.bodies).containsExactly(json("prime"), json("a") + json("b"),
          json("c") + json("d"), json("c") + json("d"));
    }
  }

  @Test
  void malformedDestinationDoesNotDrainOrReorderQueuedEvents() throws Exception {
    try (var server = new Server()) {
      var sink = new ClickHouseTxOutcomeSink(server.settings, MAPPER);
      sink.write(event("prime"));
      sink.write(event("a"));
      sink.write(event("b"));
      String endpoint = server.settings.getEndpoint();
      server.settings.setEndpoint("http://bad host");
      server.settings.setBatchSize(1);
      assertThatThrownBy(sink::flush).isInstanceOf(IllegalArgumentException.class);
      server.settings.setEndpoint(endpoint);
      sink.flush();
      assertThat(server.bodies).containsExactly(json("prime"), json("a"), json("b"));
    }
  }

  @Test
  void shutdownFailureIsBestEffortAndRetainsDataForNextFlush() throws Exception {
    try (var server = new Server()) {
      var sink = new ClickHouseTxOutcomeSink(server.settings, MAPPER);
      sink.write(event("prime"));
      sink.write(event("pending"));
      server.responses.add(500);
      sink.flushOnShutdown();
      sink.flush();
      assertThat(server.bodies).containsExactly(json("prime"), json("pending"), json("pending"));
    }
  }

  @Test
  void retainsTransactionPolicyClampingNonpositiveBatchSettings() throws Exception {
    try (var server = new Server()) {
      server.settings.setBatchSize(0);
      server.settings.setFlushIntervalMs(0);
      server.settings.setMaxBufferedEvents(0);
      var sink = new ClickHouseTxOutcomeSink(server.settings, MAPPER);
      sink.write(event("first"));
      sink.write(event("second"));
      assertThat(server.bodies).containsExactly(json("first"), json("second"));
    }
  }

  @Test
  void rejectsUnconfiguredSinkBeforeBuffering() throws Exception {
    try (var server = new Server()) {
      server.settings.setTable("");
      var sink = new ClickHouseTxOutcomeSink(server.settings, MAPPER);
      assertThatThrownBy(() -> sink.write(event("rejected")))
          .hasMessage("ClickHouse sink is enabled but endpoint/table is not configured");
      server.settings.setTable("events");
      sink.flush();
      assertThat(server.bodies).isEmpty();
    }
  }

  private static TxOutcomeEvent event(String id) {
    return new TxOutcomeEvent("2026-09-23 10:11:12.123", "swarm", "postprocessor", "pp-1",
        "trace", id, 200, 1, 42, "OK", 1, Map.of("text", "żółć"));
  }

  private static String json(String id) throws Exception { return MAPPER.writeValueAsString(event(id)) + "\n"; }

  private static final class Server implements AutoCloseable {
    final HttpServer server;
    final ClickHouseSinkProperties settings = new ClickHouseSinkProperties();
    final List<String> bodies = new CopyOnWriteArrayList<>();
    final List<String> queries = new CopyOnWriteArrayList<>();
    final List<String> auth = new CopyOnWriteArrayList<>();
    final ConcurrentLinkedQueue<Integer> responses = new ConcurrentLinkedQueue<>();
    Server() throws Exception {
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.createContext("/", exchange -> {
        bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        queries.add(exchange.getRequestURI().getRawQuery());
        auth.add(exchange.getRequestHeaders().getFirst("Authorization"));
        Integer queued = responses.poll();
        int status = queued == null ? 200 : queued;
        byte[] body = (status == 200 ? "ok" : "failed").getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
      });
      server.start();
      settings.setEndpoint(" http://127.0.0.1:" + server.getAddress().getPort() + "/ ");
      settings.setTable(" db.events ");
      settings.setUsername(" user ");
      settings.setPassword(" pass ");
      settings.setBatchSize(100);
      settings.setFlushIntervalMs(60_000);
    }
    public void close() { server.stop(0); }
  }
}
