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
import io.pockethive.worker.sdk.templating.TemplateRenderer;
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
    assertThat(out.steps()).hasSize(3); // seed + step A + step B
    assertThat(out.stepHeaders()).containsEntry("x-ph-http-seq-call-id", "B");
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
    assertThat(out.stepHeaders()).containsEntry("x-ph-http-seq-status", 200);
    assertThat(out.stepHeaders()).containsEntry("x-ph-http-seq-attempts", 3);
    assertThat(out.payload()).contains("result");
    assertThat(out.payload()).contains("location");
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
        redis,
        null
    );
  }

  private void writeTemplate(String callId) throws Exception {
    String yaml = """
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

    void enqueue(HttpCallResult result) {
      results.add(result);
    }

    java.util.List<RenderedCall> calls() {
      return List.copyOf(calls);
    }

    @Override
    public HttpCallResult execute(String baseUrl, RenderedCall call) {
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

