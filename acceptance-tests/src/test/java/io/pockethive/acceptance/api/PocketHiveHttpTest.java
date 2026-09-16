package io.pockethive.acceptance.api;

import static org.junit.jupiter.api.Assertions.*;
import io.pockethive.acceptance.support.ScriptedIngress;
import java.net.http.HttpTimeoutException;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PocketHiveHttpTest {
  @Test void sendsUtf8TextWithoutJsonQuotingAndPreservesDenial() throws Exception {
    String payload = "- name: żółć\n  enabled: true\n";
    var server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
    server.createContext("/raw", exchange -> {
      var body = exchange.getRequestBody().readAllBytes();
      boolean valid = "PUT".equals(exchange.getRequestMethod())
          && "text/plain".equals(exchange.getRequestHeaders().getFirst("Content-Type"))
          && "text/plain".equals(exchange.getRequestHeaders().getFirst("Accept"))
          && "Bearer actor-token".equals(exchange.getRequestHeaders().getFirst("Authorization"))
          && payload.equals(new String(body, java.nio.charset.StandardCharsets.UTF_8));
      exchange.sendResponseHeaders(valid ? 403 : 415, body.length);
      try (var output = exchange.getResponseBody()) { output.write(body); }
      exchange.close();
    });
    server.start();
    try (var http = new PocketHiveHttp(origin(server), Duration.ofSeconds(1))) {
      var response = http.requestText("PUT", "/raw", payload, "actor-token").expect(403);
      assertEquals(payload, response.body());
      assertThrows(IllegalArgumentException.class,
          () -> http.requestText("PUT", "https://unrelated.invalid/raw", payload, "actor-token"));
    } finally { server.stop(0); }
  }

  @Test void requestsTheSelectedRepresentationWithoutChangingTheBody() throws Exception {
    var server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
    server.createContext("/raw", exchange -> {
      String accept = exchange.getRequestHeaders().getFirst("Accept");
      byte[] body = "rate: 7.5\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
      exchange.sendResponseHeaders("text/plain".equals(accept) ? 200 : 406, body.length);
      try (var output = exchange.getResponseBody()) { output.write(body); }
      exchange.close();
    });
    server.start();
    try (var http = new PocketHiveHttp(origin(server), Duration.ofSeconds(1))) {
      assertEquals(406, http.request("GET", "/raw", null, "").status());
      var response = http.request("GET", "/raw", null, "", "text/plain").expect(200);
      assertEquals("rate: 7.5\n", response.body());
    } finally { server.stop(0); }
  }

  @Test void preservesDeniedResponseAsData() throws Exception {
    try (var ingress = new ScriptedIngress(); var http = new PocketHiveHttp(ingress.origin(), Duration.ofSeconds(1))) {
      ingress.reply("GET", "/api/protected", 403, Map.of("message", "denied"));
      var response = http.request("GET", "/api/protected", null, "");
      assertEquals(403, response.status());
      assertEquals("denied", http.tree(response).required("message").asText());
    }
  }
  @Test void refusesAnOffOriginOperationUrlBeforeSendingCredentials() throws Exception {
    try (var ingress = new ScriptedIngress(); var http = new PocketHiveHttp(ingress.origin(), Duration.ofSeconds(1))) {
      assertThrows(IllegalArgumentException.class,
          () -> http.request("GET", "https://unrelated.invalid/api/operations/1", null, "secret"));
    }
  }
  @Test void enforcesTheRemainingRequestBudget() throws Exception {
    try (var ingress = new ScriptedIngress(); var http = new PocketHiveHttp(ingress.origin(), Duration.ofSeconds(5))) {
      ingress.replyAfter("GET", "/api/slow", 200, Map.of(), Duration.ofMillis(300));
      assertThrows(HttpTimeoutException.class,
          () -> http.request("GET", "/api/slow", null, "", Duration.ofMillis(100)));
    }
  }
  @Test void doesNotFollowRedirectResponses() throws Exception {
    try (var ingress = new ScriptedIngress(); var http = new PocketHiveHttp(ingress.origin(), Duration.ofSeconds(1))) {
      ingress.reply("POST", "/api/action", 302, Map.of("message", "redirect"));
      assertEquals(302, http.request("POST", "/api/action", Map.of(), "").status());
    }
  }

  @Test void timesOutAndClosesWhileTheBodyIsStillBlocked() throws Exception {
    var headers = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var server = stalledResponse(headers, release);
    try {
      assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
        try (var http = new PocketHiveHttp(origin(server), Duration.ofSeconds(10))) {
          assertThrows(HttpTimeoutException.class,
              () -> http.request("GET", "/stalled-body", null, "", Duration.ofMillis(300)));
          assertEquals(0, headers.getCount(), "The server must have sent headers before timeout");
          assertEquals(1, release.getCount(), "The body has not been released by the test");
        }
      });
    } finally { release.countDown(); server.stop(0); }
  }

  @Test void interruptionCancelsTheBodyAndAllowsTheClientToClose() throws Exception {
    var headers = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var server = stalledResponse(headers, release);
    var failure = new AtomicReference<Throwable>();
    Thread requester = Thread.ofVirtual().unstarted(() -> {
      try (var http = new PocketHiveHttp(origin(server), Duration.ofSeconds(10))) {
        http.request("GET", "/stalled-body", null, "");
      } catch (Throwable error) { failure.set(error); }
    });
    try {
      requester.start();
      assertTrue(headers.await(2, TimeUnit.SECONDS), "Response headers were not sent");
      requester.interrupt();
      requester.join(Duration.ofSeconds(2));
      assertFalse(requester.isAlive(), "Client close must not wait for the stalled body");
      assertInstanceOf(InterruptedException.class, failure.get());
    } finally {
      release.countDown();
      server.stop(0);
      requester.interrupt();
      requester.join(Duration.ofSeconds(2));
    }
  }

  private static URI origin(HttpServer server) {
    return URI.create("http://localhost:" + server.getAddress().getPort() + "/");
  }
  private static HttpServer stalledResponse(CountDownLatch headers, CountDownLatch release) throws Exception {
    var server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
    server.createContext("/stalled-body", exchange -> {
      try {
        exchange.getRequestBody().readAllBytes();
        exchange.sendResponseHeaders(200, 2);
        exchange.getResponseBody().write('{');
        exchange.getResponseBody().flush();
        headers.countDown();
        release.await(5, TimeUnit.SECONDS);
      } catch (InterruptedException error) {
        Thread.currentThread().interrupt();
      } finally { exchange.close(); }
    });
    server.start();
    return server;
  }
}
