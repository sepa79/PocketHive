package io.pockethive.httpsequence;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ApacheHttpCallExecutorIntegrationTest {

  private final AtomicReference<String> receivedBody = new AtomicReference<>();
  private HttpServer server;
  private CloseableHttpClient client;

  @BeforeEach
  void startServer() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/base/call", this::respond);
    server.start();
    client = HttpClients.createDefault();
  }

  @AfterEach
  void stopServer() throws Exception {
    client.close();
    server.stop(0);
  }

  @Test
  void executesOnlyTheResolvedUriProvidedByTheResolver() throws Exception {
    ApacheHttpCallExecutor executor = new ApacheHttpCallExecutor(client);
    URI target = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/base/call?source=resolved");
    HttpCallExecutor.RenderedCall call = new HttpCallExecutor.RenderedCall(
        "POST", "/must-not-be-appended", "payload", Map.of("Content-Type", "text/plain"));

    HttpCallExecutor.HttpCallResult result = executor.execute(target, call);

    assertThat(result.statusCode()).isEqualTo(201);
    assertThat(result.body()).isEqualTo("accepted");
    assertThat(receivedBody).hasValue("payload");
  }

  private void respond(HttpExchange exchange) {
    try (exchange) {
      receivedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
      byte[] response = "accepted".getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(201, response.length);
      exchange.getResponseBody().write(response);
    } catch (Exception ex) {
      throw new IllegalStateException(ex);
    }
  }
}
