package io.pockethive.httpsequence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import io.pockethive.observability.ObservabilityContext;
import io.pockethive.requesttemplates.TemplateLoader;
import io.pockethive.worker.sdk.api.StatusPublisher;
import io.pockethive.worker.sdk.api.WorkItem;
import io.pockethive.worker.sdk.api.WorkerContext;
import io.pockethive.worker.sdk.api.WorkerInfo;
import io.pockethive.worker.sdk.config.RedisSequenceProperties;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HttpSequenceMultiEndpointAcceptanceTest {

  private static final Logger LOG = LoggerFactory.getLogger(HttpSequenceMultiEndpointAcceptanceTest.class);

  @TempDir
  Path templates;

  private final List<HttpServer> servers = new ArrayList<>();
  private CloseableHttpClient client;

  @AfterEach
  void stopServers() throws Exception {
    if (client != null) {
      client.close();
    }
    servers.forEach(server -> server.stop(0));
  }

  @Test
  void executesACompatibleJourneyAcrossThreeDifferentPortsAndBasePaths() throws Exception {
    List<String> visits = Collections.synchronizedList(new ArrayList<>());
    String workerBase = server("/worker/first", "worker", visits) + "/worker";
    String sutBase = server("/accounts/second", "sut", visits) + "/accounts";
    String literalBase = server("/audit/third", "literal", visits) + "/audit";
    writeTemplate("first", "/first");
    writeTemplate("second", "/second");
    writeTemplate("third", "/third");

    HttpSequenceRunner runner = newRunner();
    HttpSequenceWorkerConfig config = new HttpSequenceWorkerConfig(
        workerBase,
        templates.toString(),
        "journey",
        1,
        List.of(
            step("worker", "first", null, null),
            step("literal", "third", null, literalBase),
            step("sut", "second", "accounts", null)
        ),
        new HttpSequenceWorkerConfig.DebugCapture(
            HttpSequenceWorkerConfig.DebugCaptureMode.NONE, 0.0, 1024, 4096, false, false, 0, 60),
        Map.of(),
        Map.of("authProfile", Map.of("sut", Map.of(
            "id", "customer-platform",
            "endpoints", Map.of("accounts", Map.of("kind", "HTTP", "baseUrl", sutBase)))))
    );
    WorkerInfo info = new WorkerInfo("http-sequence", "swarm-acceptance", "instance-1", null, null);
    WorkItem seed = WorkItem.text(info, "{\"journey\":true}").contentType("application/json").build();

    WorkItem result = runner.run(seed, new AcceptanceWorkerContext(info), config);

    assertThat(visits).containsExactly("worker", "literal", "sut");
    assertThat(result.steps()).hasSize(4);
    assertThat(result.stepHeaders())
        .containsEntry(HttpSequenceHeaders.STATUS, 200)
        .containsEntry(HttpSequenceHeaders.TARGET_SOURCE,
            HttpSequenceTargetResolver.TargetSource.SUT_ENDPOINT.name())
        .containsEntry(HttpSequenceHeaders.SUT_ENDPOINT_ID, "accounts");
  }

  @Test
  void rejectsEncodedTraversalBeforeAnyHttpIo() throws Exception {
    AtomicInteger requestCount = new AtomicInteger();
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/", exchange -> {
      try (exchange) {
        requestCount.incrementAndGet();
        exchange.sendResponseHeaders(204, -1);
      }
    });
    server.start();
    servers.add(server);
    writeTemplate("escape", "/safe/%2e%2e%2fadmin");

    HttpSequenceRunner runner = newRunner();
    HttpSequenceWorkerConfig config = new HttpSequenceWorkerConfig(
        "http://127.0.0.1:" + server.getAddress().getPort() + "/root",
        templates.toString(),
        "journey",
        1,
        List.of(step("escape", "escape", null, null)),
        new HttpSequenceWorkerConfig.DebugCapture(
            HttpSequenceWorkerConfig.DebugCaptureMode.NONE, 0.0, 1024, 4096, false, false, 0, 60),
        Map.of());
    WorkerInfo info = new WorkerInfo("http-sequence", "swarm-acceptance", "instance-1", null, null);
    WorkItem seed = WorkItem.text(info, "{\"journey\":true}").contentType("application/json").build();

    assertThatThrownBy(() -> runner.run(seed, new AcceptanceWorkerContext(info), config))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Rendered HTTP path must not contain traversal segments");
    assertThat(requestCount.get()).isZero();
  }

  private HttpSequenceRunner newRunner() {
    client = HttpClients.createDefault();
    RedisSequenceProperties redis = new RedisSequenceProperties();
    redis.setEnabled(false);
    return new HttpSequenceRunner(
        new ObjectMapper().findAndRegisterModules(),
        Clock.systemUTC(),
        new io.pockethive.templating.PebbleTemplateRenderer(),
        new TemplateLoader(),
        new ApacheHttpCallExecutor(client),
        new DefaultHttpSequenceTargetResolver(),
        redis);
  }

  private String server(String path, String marker, List<String> visits) throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(path, exchange -> respond(exchange, marker, visits));
    server.start();
    servers.add(server);
    return "http://127.0.0.1:" + server.getAddress().getPort();
  }

  private static void respond(HttpExchange exchange, String marker, List<String> visits) {
    try (exchange) {
      visits.add(marker);
      byte[] body = ("{\"visited\":\"" + marker + "\"}").getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().add("Content-Type", "application/json");
      exchange.sendResponseHeaders(200, body.length);
      exchange.getResponseBody().write(body);
    } catch (Exception ex) {
      throw new IllegalStateException(ex);
    }
  }

  private void writeTemplate(String callId, String path) throws Exception {
    Files.writeString(templates.resolve(callId + ".yaml"), """
        protocol: HTTP
        callId: %s
        method: GET
        pathTemplate: %s
        headersTemplate: {}
        bodyTemplate: ""
        """.formatted(callId, path));
  }

  private static HttpSequenceWorkerConfig.Step step(
      String id, String callId, String sutEndpointId, String baseUrl) {
    return new HttpSequenceWorkerConfig.Step(
        id, callId, null, false, null, List.of(), List.of(), sutEndpointId, baseUrl);
  }

  private record AcceptanceWorkerContext(WorkerInfo info) implements WorkerContext {

    @Override
    public boolean enabled() {
      return true;
    }

    @Override
    public <C> C config(Class<C> type) {
      return null;
    }

    @Override
    public StatusPublisher statusPublisher() {
      return StatusPublisher.NO_OP;
    }

    @Override
    public Logger logger() {
      return LOG;
    }

    @Override
    public SimpleMeterRegistry meterRegistry() {
      return new SimpleMeterRegistry();
    }

    @Override
    public ObservationRegistry observationRegistry() {
      return ObservationRegistry.create();
    }

    @Override
    public ObservabilityContext observabilityContext() {
      return new ObservabilityContext();
    }
  }
}
