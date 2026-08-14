package io.pockethive.httpsequence;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import io.pockethive.observability.ObservabilityContext;
import io.pockethive.requesttemplates.TemplateLoader;
import io.pockethive.worker.sdk.api.StatusPublisher;
import io.pockethive.worker.sdk.api.WorkItem;
import io.pockethive.worker.sdk.api.WorkerContext;
import io.pockethive.worker.sdk.api.WorkerInfo;
import io.pockethive.worker.sdk.config.RedisSequenceProperties;
import io.pockethive.templating.TemplateRenderer;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HttpSequenceRunnerTest {

  private static final Logger LOG = LoggerFactory.getLogger(HttpSequenceRunnerTest.class);

  @TempDir
  Path tempDir;

  @Test
  void continueOnNon2xxRunsNextStep() throws Exception {
    writeTemplate("A");
    writeTemplate("B");

    RecordingExecutor executor = new RecordingExecutor();
    executor.enqueue(new HttpCallExecutor.HttpCallResult(500, Map.of(), "fail", null));
    executor.enqueue(new HttpCallExecutor.HttpCallResult(200, Map.of(), "ok", null));

    HttpSequenceRunner runner = newRunner(executor);
    WorkerInfo info = new WorkerInfo("http-sequence", "swarm-1", "inst-1", null, null);
    WorkItem seed = WorkItem.text(info, "{\"seed\":true}").contentType("application/json").build();

    HttpSequenceWorkerConfig config = new HttpSequenceWorkerConfig(
        "http://sut",
        tempDir.toString(),
        "default",
        1,
        List.of(
            new HttpSequenceWorkerConfig.Step("s1", "A", null, true, null, List.of(), List.of()),
            new HttpSequenceWorkerConfig.Step("s2", "B", null, false, null, List.of(), List.of())
        ),
        new HttpSequenceWorkerConfig.DebugCapture(HttpSequenceWorkerConfig.DebugCaptureMode.NONE, 0.0, 1, 1, false, false, 0, 1),
        Map.of()
    );

    WorkItem out = runner.run(seed, new TestWorkerContext(info), config);

    assertThat(executor.calls()).hasSize(2);
    assertThat(executor.targets()).containsExactly(URI.create("http://sut/a"), URI.create("http://sut/b"));
    assertThat(out.steps()).hasSize(3); // seed + step A + step B
    assertThat(out.stepHeaders()).containsEntry("x-ph-http-seq-call-id", "B");
    assertThat(out.stepHeaders()).containsEntry(
        HttpSequenceHeaders.TARGET_SOURCE, HttpSequenceTargetResolver.TargetSource.WORKER_BASE_URL.name());
  }

  @Test
  void retryOn5xxEventuallySucceeds() throws Exception {
    writeTemplate("A");

    RecordingExecutor executor = new RecordingExecutor();
    executor.enqueue(new HttpCallExecutor.HttpCallResult(500, Map.of(), "fail-1", null));
    executor.enqueue(new HttpCallExecutor.HttpCallResult(500, Map.of(), "fail-2", null));
    executor.enqueue(new HttpCallExecutor.HttpCallResult(200, Map.of("location", List.of("/ok")), "ok", null));

    HttpSequenceRunner runner = newRunner(executor);
    WorkerInfo info = new WorkerInfo("http-sequence", "swarm-1", "inst-1", null, null);
    WorkItem seed = WorkItem.text(info, "{\"seed\":true}").contentType("application/json").build();

    HttpSequenceWorkerConfig.Retry retry = new HttpSequenceWorkerConfig.Retry(
        3,
        0,
        1.0,
        0,
        List.of("5xx")
    );
    HttpSequenceWorkerConfig.Step step = new HttpSequenceWorkerConfig.Step(
        "s1",
        "A",
        null,
        false,
        retry,
        List.of(new HttpSequenceWorkerConfig.Extract(null, "Location", false, "result.location", true)),
        List.of()
    );

    HttpSequenceWorkerConfig config = new HttpSequenceWorkerConfig(
        "http://sut",
        tempDir.toString(),
        "default",
        1,
        List.of(step),
        new HttpSequenceWorkerConfig.DebugCapture(HttpSequenceWorkerConfig.DebugCaptureMode.NONE, 0.0, 1, 1, false, false, 0, 1),
        Map.of()
    );

    WorkItem out = runner.run(seed, new TestWorkerContext(info), config);

    assertThat(executor.calls()).hasSize(3);
    assertThat(executor.targets()).containsOnly(URI.create("http://sut/a"));
    assertThat(out.stepHeaders()).containsEntry("x-ph-http-seq-status", 200);
    assertThat(out.stepHeaders()).containsEntry("x-ph-http-seq-attempts", 3);
    assertThat(out.payload()).contains("result");
    assertThat(out.payload()).contains("location");
  }

  @Test
  void retryKeepsTheSameResolvedSutEndpointUri() throws Exception {
    writeTemplate("A");
    RecordingExecutor executor = new RecordingExecutor();
    executor.enqueue(new HttpCallExecutor.HttpCallResult(503, Map.of(), "retry", null));
    executor.enqueue(new HttpCallExecutor.HttpCallResult(200, Map.of(), "ok", null));
    HttpSequenceRunner runner = newRunner(executor);
    WorkerInfo info = new WorkerInfo("http-sequence", "swarm-1", "inst-1", null, null);
    WorkItem seed = WorkItem.text(info, "{\"seed\":true}").contentType("application/json").build();
    HttpSequenceWorkerConfig.Retry retry = new HttpSequenceWorkerConfig.Retry(
        2, 0, 1.0, 0, List.of("5xx"));
    HttpSequenceWorkerConfig.Step step = new HttpSequenceWorkerConfig.Step(
        "sut", "A", null, false, retry, List.of(), List.of(), "accounts", null);
    HttpSequenceWorkerConfig config = new HttpSequenceWorkerConfig(
        "http://worker:8080",
        tempDir.toString(),
        "default",
        1,
        List.of(step),
        new HttpSequenceWorkerConfig.DebugCapture(
            HttpSequenceWorkerConfig.DebugCaptureMode.NONE, 0.0, 1, 1, false, false, 0, 1),
        Map.of(),
        Map.of("authProfile", Map.of("sut", Map.of(
            "id", "sut-1",
            "endpoints", Map.of("accounts", Map.of(
                "kind", "HTTP", "baseUrl", "http://accounts:9080/api")))))
    );

    WorkItem out = runner.run(seed, new TestWorkerContext(info), config);

    assertThat(executor.targets()).containsExactly(
        URI.create("http://accounts:9080/api/a"),
        URI.create("http://accounts:9080/api/a"));
    assertThat(out.stepHeaders())
        .containsEntry(HttpSequenceHeaders.ATTEMPTS, 2)
        .containsEntry(HttpSequenceHeaders.TARGET_SOURCE,
            HttpSequenceTargetResolver.TargetSource.SUT_ENDPOINT.name())
        .containsEntry(HttpSequenceHeaders.SUT_ENDPOINT_ID, "accounts");
  }

  @Test
  void throwsWhenSequenceStepTemplateIsMissing() throws Exception {
    RecordingExecutor executor = new RecordingExecutor();
    HttpSequenceRunner runner = newRunner(executor);
    WorkerInfo info = new WorkerInfo("http-sequence", "swarm-1", "inst-1", null, null);
    WorkItem seed = WorkItem.text(info, "{\"seed\":true}").contentType("application/json").build();

    HttpSequenceWorkerConfig config = new HttpSequenceWorkerConfig(
        "http://sut",
        tempDir.toString(),
        "default",
        1,
        List.of(new HttpSequenceWorkerConfig.Step("s1", "MISSING", null, false, null, List.of(), List.of())),
        new HttpSequenceWorkerConfig.DebugCapture(HttpSequenceWorkerConfig.DebugCaptureMode.NONE, 0.0, 1, 1, false, false, 0, 1),
        Map.of()
    );

    assertThatThrownBy(() -> runner.run(seed, new TestWorkerContext(info), config))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Missing HTTP template");
  }

  @Test
  void appliesAuthRefHeadersPerSequenceStep() throws Exception {
    Path templates = Files.createDirectories(tempDir.resolve("templates"));
    Files.writeString(tempDir.resolve("authProfiles.yaml"), """
        profiles:
          "api:static":
            type: STATIC_TOKEN
            storage:
              mode: NONE
            token: sequence-token
        """);
    Files.writeString(templates.resolve("A.yaml"), """
        protocol: HTTP
        callId: A
        method: GET
        pathTemplate: /a
        headersTemplate: {}
        bodyTemplate: ""
        authRef:
          profileId: "api:static"
          applyAs: HTTP_AUTHORIZATION_BEARER
        """);

    RecordingExecutor executor = new RecordingExecutor();
    HttpSequenceRunner runner = newRunner(executor);
    WorkerInfo info = new WorkerInfo("http-sequence", "swarm-1", "inst-1", null, null);
    WorkItem seed = WorkItem.text(info, "{\"seed\":true}").contentType("application/json").build();

    HttpSequenceWorkerConfig config = new HttpSequenceWorkerConfig(
        "http://sut",
        templates.toString(),
        "default",
        1,
        List.of(new HttpSequenceWorkerConfig.Step("s1", "A", null, false, null, List.of(), List.of())),
        new HttpSequenceWorkerConfig.DebugCapture(HttpSequenceWorkerConfig.DebugCaptureMode.NONE, 0.0, 1, 1, false, false, 0, 1),
        Map.of()
    );

    runner.run(seed, new TestWorkerContext(info), config);

    assertThat(executor.calls()).singleElement()
        .satisfies(call -> assertThat(call.headers()).containsEntry("Authorization", "Bearer sequence-token"));
  }

  @Test
  void appliesAuthProfileUsingSutContextPerSequenceStep() throws Exception {
    Path templates = Files.createDirectories(tempDir.resolve("templates"));
    Files.writeString(tempDir.resolve("authProfiles.yaml"), """
        profiles:
          "api:sut":
            type: STATIC_TOKEN
            storage:
              mode: NONE
            token: "{{ sut.endpoints['default'].baseUrl }}/sequence-token"
        """);
    Files.writeString(templates.resolve("A.yaml"), """
        protocol: HTTP
        callId: A
        method: GET
        pathTemplate: /a
        headersTemplate: {}
        bodyTemplate: ""
        authRef:
          profileId: "api:sut"
          applyAs: HTTP_AUTHORIZATION_BEARER
        """);

    RecordingExecutor executor = new RecordingExecutor();
    HttpSequenceRunner runner = new HttpSequenceRunner(
        new ObjectMapper().findAndRegisterModules(),
        Clock.systemUTC(),
        new io.pockethive.templating.PebbleTemplateRenderer(),
        new TemplateLoader(),
        executor,
        new DefaultHttpSequenceTargetResolver(),
        new RedisSequenceProperties());
    WorkerInfo info = new WorkerInfo("http-sequence", "swarm-1", "inst-1", null, null);
    WorkItem seed = WorkItem.text(info, "{\"seed\":true}").contentType("application/json").build();

    HttpSequenceWorkerConfig config = new HttpSequenceWorkerConfig(
        "http://sut",
        templates.toString(),
        "default",
        1,
        List.of(new HttpSequenceWorkerConfig.Step("s1", "A", null, false, null, List.of(), List.of())),
        new HttpSequenceWorkerConfig.DebugCapture(HttpSequenceWorkerConfig.DebugCaptureMode.NONE, 0.0, 1, 1, false, false, 0, 1),
        Map.of(),
        Map.of("authProfile", Map.of("sut", Map.of(
            "id", "wiremock-local",
            "endpoints", Map.of("default", Map.of("baseUrl", "http://wiremock:8080")))))
    );

    runner.run(seed, new TestWorkerContext(info), config);

    assertThat(executor.calls()).singleElement()
        .satisfies(call -> assertThat(call.headers())
            .containsEntry("Authorization", "Bearer http://wiremock:8080/sequence-token"));
  }

  @Test
  void runsOneJourneyAcrossWorkerSutAndLiteralTargets() throws Exception {
    writeTemplate("A");
    writeTemplate("B");
    writeTemplate("C");

    RecordingExecutor executor = new RecordingExecutor();
    HttpSequenceRunner runner = newRunner(executor);
    WorkerInfo info = new WorkerInfo("http-sequence", "swarm-1", "inst-1", null, null);
    WorkItem seed = WorkItem.text(info, "{\"seed\":true}").contentType("application/json").build();
    HttpSequenceWorkerConfig config = new HttpSequenceWorkerConfig(
        "http://worker:8080/root",
        tempDir.toString(),
        "default",
        1,
        List.of(
            new HttpSequenceWorkerConfig.Step("worker", "A", null, false, null, List.of(), List.of()),
            new HttpSequenceWorkerConfig.Step(
                "sut", "B", null, false, null, List.of(), List.of(), "accounts", null),
            new HttpSequenceWorkerConfig.Step(
                "literal", "C", null, false, null, List.of(), List.of(), null, "http://audit:9080/audit")
        ),
        new HttpSequenceWorkerConfig.DebugCapture(
            HttpSequenceWorkerConfig.DebugCaptureMode.NONE, 0.0, 1, 1, false, false, 0, 1),
        Map.of(),
        Map.of("authProfile", Map.of("sut", Map.of(
            "id", "sut-1",
            "endpoints", Map.of("accounts", Map.of(
                "kind", "HTTPS", "baseUrl", "https://accounts:10443/api")))))
    );

    WorkItem out = runner.run(seed, new TestWorkerContext(info), config);

    assertThat(executor.targets()).containsExactly(
        URI.create("http://worker:8080/root/a"),
        URI.create("https://accounts:10443/api/b"),
        URI.create("http://audit:9080/audit/c"));
    assertThat(out.stepHeaders())
        .containsEntry(HttpSequenceHeaders.TARGET_SOURCE,
            HttpSequenceTargetResolver.TargetSource.STEP_BASE_URL.name())
        .doesNotContainKey(HttpSequenceHeaders.SUT_ENDPOINT_ID);
  }

  @Test
  void invalidLaterOverrideFailsBeforeEarlierStepCanSendTraffic() throws Exception {
    writeTemplate("A");
    RecordingExecutor executor = new RecordingExecutor();
    HttpSequenceRunner runner = newRunner(executor);
    WorkerInfo info = new WorkerInfo("http-sequence", "swarm-1", "inst-1", null, null);
    WorkItem seed = WorkItem.text(info, "{\"seed\":true}").contentType("application/json").build();
    HttpSequenceWorkerConfig config = new HttpSequenceWorkerConfig(
        "http://worker:8080",
        tempDir.toString(),
        "default",
        1,
        List.of(
            new HttpSequenceWorkerConfig.Step("worker", "A", null, false, null, List.of(), List.of()),
            new HttpSequenceWorkerConfig.Step(
                "missing", "B", null, false, null, List.of(), List.of(), "missing", null)
        ),
        new HttpSequenceWorkerConfig.DebugCapture(
            HttpSequenceWorkerConfig.DebugCaptureMode.NONE, 0.0, 1, 1, false, false, 0, 1),
        Map.of(),
        Map.of("authProfile", Map.of("sut", Map.of("id", "sut-1", "endpoints", Map.of())))
    );

    assertThatThrownBy(() -> runner.run(seed, new TestWorkerContext(info), config))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("steps[1].sutEndpointId references unknown SUT endpoint 'missing'");
    assertThat(executor.targets()).isEmpty();
  }

  private HttpSequenceRunner newRunner(RecordingExecutor executor) {
    ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    TemplateRenderer templateRenderer = (template, context) -> template == null ? "" : template;
    RedisSequenceProperties redis = new RedisSequenceProperties();
    redis.setEnabled(false);
    return new HttpSequenceRunner(
        mapper,
        Clock.systemUTC(),
        templateRenderer,
        new TemplateLoader(),
        executor,
        new DefaultHttpSequenceTargetResolver(),
        redis
    );
  }

  private void writeTemplate(String callId) throws Exception {
    String yaml = """
        protocol: HTTP
        callId: %s
        method: GET
        pathTemplate: /%s
        headersTemplate: {}
        bodyTemplate: ""
        """.formatted(callId, callId.toLowerCase());
    Files.writeString(tempDir.resolve(callId + ".yaml"), yaml);
  }

  private static final class RecordingExecutor implements HttpCallExecutor {
    private final ArrayDeque<HttpCallResult> results = new ArrayDeque<>();
    private final java.util.List<RenderedCall> calls = new java.util.ArrayList<>();
    private final java.util.List<URI> targets = new java.util.ArrayList<>();

    void enqueue(HttpCallResult result) {
      results.add(result);
    }

    java.util.List<RenderedCall> calls() {
      return List.copyOf(calls);
    }

    java.util.List<URI> targets() {
      return List.copyOf(targets);
    }

    @Override
    public HttpCallResult execute(URI target, RenderedCall call) {
      targets.add(target);
      calls.add(call);
      return results.isEmpty()
          ? new HttpCallResult(200, Map.of(), "", null)
          : results.removeFirst();
    }
  }

  private static final class TestWorkerContext implements WorkerContext {

    private final WorkerInfo info;

    private TestWorkerContext(WorkerInfo info) {
      this.info = info;
    }

    @Override
    public WorkerInfo info() {
      return info;
    }

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
