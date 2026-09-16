package io.pockethive.acceptance.support;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.function.Function;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicReference;

/** Test-owned HTTP stub for framework boundary tests; never connects to PocketHive services. */
public final class ScriptedIngress implements AutoCloseable {
  private final HttpServer server;
  private final ConcurrentLinkedQueue<Reply> replies = new ConcurrentLinkedQueue<>();
  private final AtomicReference<Throwable> failure = new AtomicReference<>();
  private final ObjectMapper json = JsonMapper.builder().findAndAddModules()
      .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).build();

  public ScriptedIngress() throws IOException {
    server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
    server.createContext("/", exchange -> {
      try {
        Reply next = replies.poll();
        assertNotNull(next, "Unexpected " + exchange.getRequestMethod() + " " + exchange.getRequestURI());
        assertEquals(next.method(), exchange.getRequestMethod());
        assertEquals(next.path(), exchange.getRequestURI().toString());
        JsonNode request = json.readTree(exchange.getRequestBody().readAllBytes());
        if (!next.delay().isZero()) Thread.sleep(next.delay());
        byte[] body = json.writeValueAsBytes(next.body().apply(request));
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(next.status(), body.length);
        exchange.getResponseBody().write(body);
      } catch (IOException disconnected) {
        // A timeout test intentionally disconnects before its delayed response.
      } catch (Throwable problem) {
        failure.compareAndSet(null, problem);
        exchange.sendResponseHeaders(500, -1);
      } finally { exchange.close(); }
    });
    server.start();
  }
  public URI origin() { return URI.create("http://localhost:" + server.getAddress().getPort() + "/"); }
  public ScriptedIngress reply(String method, String path, int status, Object body) {
    return replyAfter(method, path, status, body, Duration.ZERO);
  }
  public ScriptedIngress replyAfter(String method, String path, int status, Object body, Duration delay) {
    replies.add(new Reply(method, path, status, ignored -> body, delay));
    return this;
  }
  public ScriptedIngress replyWith(String method, String path, int status, Function<JsonNode, Object> body) {
    replies.add(new Reply(method, path, status, body, Duration.ZERO));
    return this;
  }
  @Override public void close() {
    server.stop(0);
    if (failure.get() != null) throw new AssertionError("Scripted ingress request mismatch", failure.get());
    assertTrue(replies.isEmpty(), "Not all scripted requests were sent: " + replies);
  }
  private record Reply(String method, String path, int status, Function<JsonNode, Object> body, Duration delay) {}
}
