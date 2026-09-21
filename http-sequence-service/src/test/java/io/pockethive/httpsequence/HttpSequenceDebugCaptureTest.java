package io.pockethive.httpsequence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.pockethive.requesttemplates.files.TemplateLoader;
import io.pockethive.work.api.StatusPublisher;
import io.pockethive.work.api.WorkItem;
import io.pockethive.work.api.WorkerContext;
import io.pockethive.work.api.WorkerInfo;
import io.pockethive.worker.sdk.config.RedisSequenceProperties;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;
import org.slf4j.LoggerFactory;

/** Real template/runner/Redis capture behavior against a disposable, explicitly configured Redis fixture. */
@EnabledIfEnvironmentVariable(named = "AUTH_REDIS_TEST_HOST", matches = ".+")
@EnabledIfEnvironmentVariable(named = "AUTH_REDIS_TEST_PORT", matches = "[0-9]+")
@Timeout(30)
class HttpSequenceDebugCaptureTest {
  private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();
  private static final String REDACTED = "[REDACTED]";
  @TempDir Path temporary;

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 2})
  void storedCaptureRedactsCredentialHeadersWithoutChangingTrafficOrCaptureMetadata(int casing) throws Exception {
    String[][] names = {
        {"Authorization", "Proxy-Authorization", "Cookie", "Set-Cookie"},
        {"authorization", "proxy-authorization", "cookie", "set-cookie"},
        {"aUtHoRiZaTiOn", "pRoXy-AuThOrIzAtIoN", "cOoKiE", "sEt-CoOkIe"}
    };
    String[] header = names[casing];
    writeTemplate(header);
    Map<String, List<String>> responseHeaders = Map.of(
        header[0], List.of("Bearer response-token"),
        header[1], List.of("Basic response-proxy"),
        header[2], List.of("response-session=private"),
        header[3], List.of("response-cookie=first", "response-cookie=second"),
        "X-Safe", List.of("one", "two"));
    try (Fixture fixture = new Fixture(true, capture(HttpSequenceWorkerConfig.DebugCaptureMode.ALWAYS),
        new HttpCallExecutor.HttpCallResult(200, responseHeaders, "response-body", null))) {
      WorkItem output = fixture.run();
      String key = debugKey(output);
      String stored = fixture.observer.sync().get(key);
      JsonNode node = MAPPER.readTree(stored);
      for (String name : header) {
        assertThat(node.path("request").path("headers").path(name).isTextual()).isTrue();
        assertThat(node.path("request").path("headers").path(name).asText()).isEqualTo(REDACTED);
        JsonNode responseValues = node.path("headers").path(name);
        assertThat(responseValues.isArray()).isTrue();
        assertThat(responseValues.size()).isEqualTo(responseHeaders.get(name).size());
        responseValues.forEach(value -> assertThat(value.asText()).isEqualTo(REDACTED));
      }
      assertThat(node.path("request").path("headers").path("X-Safe").asText()).isEqualTo("visible");
      assertThat(node.path("headers").path("X-Safe")).isEqualTo(MAPPER.valueToTree(List.of("one", "two")));
      assertThat(stored).doesNotContain("request-token", "request-proxy", "request-cookie", "request-set-cookie",
          "response-token", "response-proxy", "response-session", "response-cookie");
      assertThat(fixture.sent).singleElement().satisfies(call -> assertThat(call.headers()).containsAllEntriesOf(Map.of(
          header[0], "Bearer request-token", header[1], "Basic request-proxy", header[2], "request-cookie=private",
          header[3], "request-set-cookie=private", "X-Safe", "visible")));
      assertThat(responseHeaders.get(header[3])).containsExactly("response-cookie=first", "response-cookie=second");
      assertThat(key).startsWith("ph:debug:http-seq:" + fixture.info.swarmId() + ":http-sequence:capture-worker:");
      assertThat(fixture.observer.sync().ttl(key)).isBetween(1L, 90L);
      assertThat(node.path("serviceId").asText()).isEqualTo("default");
      assertThat(node.path("callId").asText()).isEqualTo("A");
      assertThat(node.path("status").asInt()).isEqualTo(200);
      assertThat(node.path("targetSource").asText()).isEqualTo("WORKER_BASE_URL");
      assertThat(node.path("request").path("method").asText()).isEqualTo("POST");
      assertThat(node.path("request").path("url").asText()).isEqualTo("http://capture.test/a");
      assertThat(node.path("request").path("body").asText()).isEqualTo("request-");
      assertThat(node.path("body").asText()).isEqualTo("response");
      assertThat(output.stepHeaders()).containsEntry(HttpSequenceHeaders.STATUS, 200)
          .containsEntry(HttpSequenceHeaders.RESPONSE_BYTES, 13);
    }
  }

  @Test
  void defaultErrorOnlyCaptureRedactsResponseCookiesWithoutEnablingRequestCapture() throws Exception {
    writeTemplate(new String[] {"Authorization", "Proxy-Authorization", "Cookie", "Set-Cookie"});
    try (Fixture fixture = new Fixture(true, HttpSequenceWorkerConfig.DebugCapture.defaults(),
        new HttpCallExecutor.HttpCallResult(503, Map.of("sEt-CoOkIe", List.of("error-session=private")), "error", null))) {
      JsonNode captured = MAPPER.readTree(fixture.observer.sync().get(debugKey(fixture.run())));
      assertThat(captured.path("headers").path("sEt-CoOkIe")).isEqualTo(MAPPER.valueToTree(List.of(REDACTED)));
      assertThat(captured.has("request")).isFalse();
      assertThat(captured.path("status").asInt()).isEqualTo(503);
      fixture.response = new HttpCallExecutor.HttpCallResult(200, Map.of(), "ok", null);
      assertThat(fixture.run().stepHeaders()).doesNotContainKey(HttpSequenceHeaders.DEBUG_REF);
    }
  }

  @ParameterizedTest
  @CsvSource({"true,false", "false,true", "false,false"})
  void captureHeaderAndRequestFlagsRemainIndependent(boolean includeHeaders, boolean includeRequest) throws Exception {
    writeTemplate(new String[] {"Authorization", "Proxy-Authorization", "Cookie", "Set-Cookie"});
    var setting = new HttpSequenceWorkerConfig.DebugCapture(
        HttpSequenceWorkerConfig.DebugCaptureMode.ALWAYS, 0, 8, 1024, includeHeaders, includeRequest, 0, 90);
    try (Fixture fixture = new Fixture(true, setting,
        new HttpCallExecutor.HttpCallResult(200, Map.of("Set-Cookie", List.of("flag-session=private")), "ok", null))) {
      JsonNode capture = MAPPER.readTree(fixture.observer.sync().get(debugKey(fixture.run())));
      assertThat(capture.has("headers")).isEqualTo(includeHeaders);
      assertThat(capture.has("request")).isEqualTo(includeRequest);
      if (includeHeaders) {
        assertThat(capture.path("headers").path("Set-Cookie")).isEqualTo(MAPPER.valueToTree(List.of(REDACTED)));
      }
      if (includeRequest) {
        assertThat(capture.path("request").path("headers").path("Authorization").asText()).isEqualTo(REDACTED);
      }
      assertThat(capture.path("body").asText()).isEqualTo("ok");
    }
  }
  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void disabledCaptureOrDisabledRedisDoesNotAllocateAConnection(boolean disableRedis) throws Exception {
    writeTemplate(new String[] {"Authorization", "Proxy-Authorization", "Cookie", "Set-Cookie"});
    HttpSequenceWorkerConfig.DebugCapture setting = capture(disableRedis
        ? HttpSequenceWorkerConfig.DebugCaptureMode.ALWAYS : HttpSequenceWorkerConfig.DebugCaptureMode.NONE);
    try (Fixture fixture = new Fixture(!disableRedis, setting,
        new HttpCallExecutor.HttpCallResult(200, Map.of(), "ok", null))) {
      int before = fixture.clients();
      for (int attempt = 0; attempt < 3; attempt++) {
        assertThat(fixture.run().stepHeaders()).doesNotContainKey(HttpSequenceHeaders.DEBUG_REF);
      }
      assertThat(fixture.clients()).isEqualTo(before);
      closeIfSupported(fixture.runner);
      closeIfSupported(fixture.runner);
      assertThat(fixture.clients()).isEqualTo(before);
    }
  }

  @Test
  void shortLivedThreadsReuseOneConnectionAndRunnerCloseReleasesItWithoutReopening() throws Exception {
    writeTemplate(new String[] {"Authorization", "Proxy-Authorization", "Cookie", "Set-Cookie"});
    try (Fixture fixture = new Fixture(true, capture(HttpSequenceWorkerConfig.DebugCaptureMode.ALWAYS),
        new HttpCallExecutor.HttpCallResult(200, Map.of(), "ok", null))) {
      int before = fixture.clients();
      var keys = new HashSet<String>();
      for (int attempt = 0; attempt < 6; attempt++) {
        FutureTask<WorkItem> journey = new FutureTask<>(fixture::run);
        Thread thread = new Thread(journey, "debug-capture-short-lived-" + attempt);
        thread.start();
        WorkItem result = journey.get(10, TimeUnit.SECONDS);
        thread.join(1000);
        assertThat(thread.isAlive()).isFalse();
        keys.add(debugKey(result));
        assertThat(fixture.clients()).as("one capture connection must be reused after each completed thread")
            .isEqualTo(before + 1);
      }
      assertThat(keys).hasSize(6);
      closeIfSupported(fixture.runner);
      assertThat(fixture.clients()).as("runner close must release the capture connection").isEqualTo(before);
      closeIfSupported(fixture.runner);
      assertClosedRunnerDoesNotReopen(fixture);
      assertThat(fixture.clients()).isEqualTo(before);
    }
  }

  @Test
  void workerShutdownReleasesCaptureConnectionAndRemainsIdempotent() throws Exception {
    writeTemplate(new String[] {"Authorization", "Proxy-Authorization", "Cookie", "Set-Cookie"});
    HttpServer endpoint = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    endpoint.createContext("/", exchange -> {
      try (exchange) {
        exchange.getRequestBody().readAllBytes();
        byte[] body = "ok".getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
      }
    });
    endpoint.start();
    try (Fixture fixture = new Fixture(true, capture(HttpSequenceWorkerConfig.DebugCaptureMode.ALWAYS),
        new HttpCallExecutor.HttpCallResult(200, Map.of(), "ok", null))) {
      HttpSequenceWorkerImpl worker = new HttpSequenceWorkerImpl(MAPPER, null,
          (template, ignored) -> template == null ? "" : template, redis(true));
      try {
        int before = fixture.clients();
        HttpSequenceWorkerConfig config = configuration(
            "http://127.0.0.1:" + endpoint.getAddress().getPort(), capture(HttpSequenceWorkerConfig.DebugCaptureMode.ALWAYS));
        when(fixture.context.requireConfig(HttpSequenceWorkerConfig.class)).thenReturn(config);
        assertThat(debugKey(worker.onMessage(fixture.seed, fixture.context))).isNotBlank();
        assertThat(fixture.clients()).isEqualTo(before + 1);
        closeIfSupported(worker);
        assertThat(fixture.clients()).as("worker shutdown must release the capture connection").isEqualTo(before);
        closeIfSupported(worker);
        assertThat(fixture.clients()).isEqualTo(before);
      } finally {
        closeIfSupported(worker);
      }
    } finally {
      endpoint.stop(0);
    }
  }

  private static void assertClosedRunnerDoesNotReopen(Fixture fixture) {
    WorkItem output = fixture.run();
    assertThat(output.stepHeaders()).doesNotContainKey(HttpSequenceHeaders.DEBUG_REF);
  }

  private static String debugKey(WorkItem item) {
    Object value = item.stepHeaders().get(HttpSequenceHeaders.DEBUG_REF);
    assertThat(value).isInstanceOf(String.class);
    return (String) value;
  }

  private static HttpSequenceWorkerConfig.DebugCapture capture(HttpSequenceWorkerConfig.DebugCaptureMode mode) {
    return new HttpSequenceWorkerConfig.DebugCapture(mode, 0, 8, 1024, true, true, 0, 90);
  }

  private HttpSequenceWorkerConfig configuration(String baseUrl, HttpSequenceWorkerConfig.DebugCapture capture) {
    return new HttpSequenceWorkerConfig(baseUrl, temporary.toString(), "default", 1,
        List.of(new HttpSequenceWorkerConfig.Step("capture", "A", null, false, null, List.of(), List.of())), capture, Map.of());
  }

  private void writeTemplate(String[] header) throws Exception {
    Files.writeString(temporary.resolve("A.yaml"), """
        protocol: HTTP
        serviceId: default
        callId: A
        method: POST
        pathTemplate: /a
        headersTemplate:
          %s: "Bearer request-token"
          %s: "Basic request-proxy"
          %s: "request-cookie=private"
          %s: "request-set-cookie=private"
          X-Safe: visible
        bodyTemplate: "request-body"
        """.formatted((Object[]) header));
  }

  private static RedisSequenceProperties redis(boolean enabled) {
    var properties = new RedisSequenceProperties();
    properties.setEnabled(enabled);
    properties.setHost(System.getenv("AUTH_REDIS_TEST_HOST"));
    properties.setPort(Integer.parseInt(System.getenv("AUTH_REDIS_TEST_PORT")));
    return properties;
  }

  private static void closeIfSupported(Object resource) throws Exception {
    if (resource instanceof AutoCloseable closeable) {
      closeable.close();
    }
  }

  private final class Fixture implements AutoCloseable {
    final RedisClient observerClient = RedisClient.create(RedisURI.create(System.getenv("AUTH_REDIS_TEST_HOST"),
        Integer.parseInt(System.getenv("AUTH_REDIS_TEST_PORT"))));
    final StatefulRedisConnection<String, String> observer = observerClient.connect();
    final WorkerInfo info = new WorkerInfo("http-sequence", "capture-" + UUID.randomUUID(), "capture-worker", null, null);
    final WorkerContext context = mock(WorkerContext.class);
    final WorkItem seed = WorkItem.text(info, "{}").contentType("application/json").build();
    final List<HttpCallExecutor.RenderedCall> sent = new ArrayList<>();
    final HttpSequenceWorkerConfig config;
    final HttpSequenceRunner runner;
    volatile HttpCallExecutor.HttpCallResult response;

    Fixture(boolean enabled, HttpSequenceWorkerConfig.DebugCapture capture, HttpCallExecutor.HttpCallResult response) {
      this.response = response;
      when(context.info()).thenReturn(info);
      when(context.logger()).thenReturn(LoggerFactory.getLogger(HttpSequenceDebugCaptureTest.class));
      when(context.meterRegistry()).thenReturn(new SimpleMeterRegistry());
      when(context.statusPublisher()).thenReturn(StatusPublisher.NO_OP);
      config = configuration("http://capture.test", capture);
      runner = new HttpSequenceRunner(MAPPER, Clock.systemUTC(),
          (template, ignored) -> template == null ? "" : template, new TemplateLoader(),
          (target, request) -> { sent.add(request); return this.response; },
          new DefaultHttpSequenceTargetResolver(), redis(enabled));
    }

    WorkItem run() { return runner.run(seed, context, config); }

    int clients() {
      return observer.sync().info("clients").lines().filter(line -> line.startsWith("connected_clients:"))
          .mapToInt(line -> Integer.parseInt(line.substring("connected_clients:".length()).trim())).findFirst().orElseThrow();
    }

    @Override
    public void close() throws Exception {
      try {
        closeIfSupported(runner);
        List<String> keys = observer.sync().keys("ph:debug:http-seq:" + info.swarmId() + ":*");
        if (!keys.isEmpty()) observer.sync().del(keys.toArray(String[]::new));
      } finally {
        observer.close();
        observerClient.shutdown();
      }
    }
  }
}