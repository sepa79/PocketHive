package io.pockethive.requestbuilder;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.pockethive.controlplane.spring.WorkerControlPlaneProperties;
import io.pockethive.observability.ObservabilityContext;
import io.pockethive.worker.sdk.api.StatusPublisher;
import io.pockethive.worker.sdk.api.WorkItem;
import io.pockethive.worker.sdk.api.WorkerContext;
import io.pockethive.worker.sdk.api.WorkerInfo;
import io.pockethive.worker.sdk.testing.ControlPlaneTestFixtures;
import io.pockethive.worker.sdk.templating.PebbleTemplateRenderer;
import io.pockethive.worker.sdk.templating.TemplateRenderer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class RequestBuilderWorkerImplTest {

  private static final WorkerControlPlaneProperties WORKER_PROPERTIES =
      ControlPlaneTestFixtures.workerProperties("swarm", "request-builder", "instance");
  private static final WorkerInfo SEED_INFO = new WorkerInfo("ingress", "swarm", "instance", null, null);

  private RequestBuilderWorkerProperties properties;
  private TemplateRenderer templateRenderer;

  @BeforeEach
  void setUp() {
    properties = new RequestBuilderWorkerProperties(new ObjectMapper(), WORKER_PROPERTIES);
    templateRenderer = new PebbleTemplateRenderer();
  }

  @Test
  void buildsHttpEnvelopeFromTemplate() throws Exception {
    Path dir = Files.createTempDirectory("http-templates");
    Files.createDirectories(dir.resolve("default"));
    Path file = dir.resolve("default/simple-call.json");
    Files.writeString(file, """
        {
          "serviceId": "default",
          "callId": "simple",
          "method": "POST",
          "pathTemplate": "/test",
          "bodyTemplate": "{{ payload }}",
          "headersTemplate": {
            "X-Test": "yes"
          }
        }
        """);

    properties.setConfig(Map.of(
        "templateRoot", dir.toString(),
        "serviceId", "default",
        "passThroughOnMissingTemplate", true
    ));
    RequestBuilderWorkerImpl worker =
        new RequestBuilderWorkerImpl(properties, templateRenderer, new TemplateLoader(), null);

    WorkItem seed = WorkItem.text(SEED_INFO, "body").header("x-ph-call-id", "simple").build();
    RequestBuilderWorkerConfig config = new RequestBuilderWorkerConfig(
        dir.toString(), "default", true, Map.of());
    WorkerContext context = new TestWorkerContext(config);

    WorkItem result = worker.onMessage(seed, context);

    assertThat(result).isNotNull();
    assertThat(result.contentType()).isEqualTo("application/json");

    JsonNode envelope = new ObjectMapper().readTree(result.asString());
    assertThat(envelope.get("protocol").asText()).isEqualTo("HTTP");
    assertThat(envelope.get("path").asText()).isEqualTo("/test");
    assertThat(envelope.get("method").asText()).isEqualTo("POST");
    assertThat(envelope.get("body").asText()).isEqualTo("body");
    assertThat(envelope.get("headers").get("X-Test").asText()).isEqualTo("yes");
  }

  @Test
  void dropsMessageWhenCallIdMissingAndPassThroughDisabled() {
    Path dir = Path.of("does-not-matter");
    properties.setConfig(Map.of(
        "templateRoot", dir.toString(),
        "serviceId", "default",
        "passThroughOnMissingTemplate", false
    ));
    RequestBuilderWorkerImpl worker =
        new RequestBuilderWorkerImpl(properties, templateRenderer, new TemplateLoader(), null);

    WorkItem seed = WorkItem.text(SEED_INFO, "body").build();
    RequestBuilderWorkerConfig config = new RequestBuilderWorkerConfig(
        dir.toString(), "default", false, Map.of());
    WorkerContext context = new TestWorkerContext(config);

    WorkItem result = worker.onMessage(seed, context);

    assertThat(result).isNull();
  }

  @Test
  void passesThroughWhenTemplateMissingAndPassThroughEnabled() throws Exception {
    Path dir = Files.createTempDirectory("http-templates-missing");
    // Intentionally do not create any template files.
    properties.setConfig(Map.of(
        "templateRoot", dir.toString(),
        "serviceId", "default",
        "passThroughOnMissingTemplate", true
    ));
    RequestBuilderWorkerImpl worker =
        new RequestBuilderWorkerImpl(properties, templateRenderer, new TemplateLoader(), null);

    WorkItem seed = WorkItem.text(SEED_INFO, "body").header("x-ph-call-id", "unknown").build();
    RequestBuilderWorkerConfig config = new RequestBuilderWorkerConfig(
        dir.toString(), "default", true, Map.of());
    WorkerContext context = new TestWorkerContext(config);

    WorkItem result = worker.onMessage(seed, context);

    assertThat(result).isSameAs(seed);
  }

  @Test
  void buildsTcpEnvelopeFromTemplate() throws Exception {
    Path dir = Files.createTempDirectory("tcp-templates");
    Files.createDirectories(dir.resolve("default"));
    Path file = dir.resolve("default/tcp-call.json");
    Files.writeString(file, """
        {
          "serviceId": "default",
          "callId": "tcp-test",
          "protocol": "TCP",
          "behavior": "REQUEST_RESPONSE",
          "bodyTemplate": "{{ payload }}",
          "headersTemplate": {},
          "endTag": "</Document>",
          "maxBytes": 8192
        }
        """);

    properties.setConfig(Map.of(
        "templateRoot", dir.toString(),
        "serviceId", "default",
        "passThroughOnMissingTemplate", true
    ));
    RequestBuilderWorkerImpl worker =
        new RequestBuilderWorkerImpl(properties, templateRenderer, new TemplateLoader(), null);

    WorkItem seed = WorkItem.text(SEED_INFO, "test-data").header("x-ph-call-id", "tcp-test").build();
    RequestBuilderWorkerConfig config = new RequestBuilderWorkerConfig(
        dir.toString(), "default", true, Map.of());
    WorkerContext context = new TestWorkerContext(config);

    WorkItem result = worker.onMessage(seed, context);

    assertThat(result).isNotNull();
    JsonNode envelope = new ObjectMapper().readTree(result.asString());
    assertThat(envelope.get("protocol").asText()).isEqualTo("TCP");
    assertThat(envelope.get("behavior").asText()).isEqualTo("REQUEST_RESPONSE");
    assertThat(envelope.get("body").asText()).isEqualTo("test-data");
    assertThat(envelope.get("endTag").asText()).isEqualTo("</Document>");
    assertThat(envelope.get("maxBytes").asInt()).isEqualTo(8192);
  }

  @Test
  void buildsTcpEnvelopeWithoutEndTag() throws Exception {
    Path dir = Files.createTempDirectory("tcp-templates-no-endtag");
    Files.createDirectories(dir.resolve("default"));
    Path file = dir.resolve("default/tcp-streaming.json");
    Files.writeString(file, """
        {
          "serviceId": "default",
          "callId": "tcp-streaming",
          "protocol": "TCP",
          "behavior": "STREAMING",
          "bodyTemplate": "{{ payload }}",
          "headersTemplate": {},
          "maxBytes": 1024
        }
        """);

    properties.setConfig(Map.of(
        "templateRoot", dir.toString(),
        "serviceId", "default",
        "passThroughOnMissingTemplate", true
    ));
    RequestBuilderWorkerImpl worker =
        new RequestBuilderWorkerImpl(properties, templateRenderer, new TemplateLoader(), null);

    WorkItem seed = WorkItem.text(SEED_INFO, "stream-data").header("x-ph-call-id", "tcp-streaming").build();
    RequestBuilderWorkerConfig config = new RequestBuilderWorkerConfig(
        dir.toString(), "default", true, Map.of());
    WorkerContext context = new TestWorkerContext(config);

    WorkItem result = worker.onMessage(seed, context);

    assertThat(result).isNotNull();
    JsonNode envelope = new ObjectMapper().readTree(result.asString());
    assertThat(envelope.get("protocol").asText()).isEqualTo("TCP");
    assertThat(envelope.get("behavior").asText()).isEqualTo("STREAMING");
    assertThat(envelope.get("body").asText()).isEqualTo("stream-data");
    assertThat(envelope.get("maxBytes").asInt()).isEqualTo(1024);
    // endTag should not be present in the envelope
    assertThat(envelope.has("endTag")).isFalse();
  }

  @Test
  void reloadsTemplatesWhenConfigChanges() throws Exception {
    Path dir1 = Files.createTempDirectory("http-templates-1");
    Files.createDirectories(dir1.resolve("default"));
    Files.writeString(dir1.resolve("default/simple-call.json"), """
        {
          "serviceId": "default",
          "callId": "simple",
          "method": "POST",
          "pathTemplate": "/one",
          "bodyTemplate": "{{ payload }}",
          "headersTemplate": {}
        }
        """);

    Path dir2 = Files.createTempDirectory("http-templates-2");
    Files.createDirectories(dir2.resolve("default"));
    Files.writeString(dir2.resolve("default/simple-call.json"), """
        {
          "serviceId": "default",
          "callId": "simple",
          "method": "POST",
          "pathTemplate": "/two",
          "bodyTemplate": "{{ payload }}",
          "headersTemplate": {}
        }
        """);

    // Default config points at dir1.
    properties.setConfig(Map.of(
        "templateRoot", dir1.toString(),
        "serviceId", "default",
        "passThroughOnMissingTemplate", true
    ));
    RequestBuilderWorkerImpl worker =
        new RequestBuilderWorkerImpl(properties, templateRenderer, new TemplateLoader(), null);

    WorkItem seed = WorkItem.text(SEED_INFO, "body").header("x-ph-call-id", "simple").build();

    // First call uses dir1 config.
    RequestBuilderWorkerConfig config1 = new RequestBuilderWorkerConfig(
        dir1.toString(), "default", true, Map.of());
    WorkerContext ctx1 = new TestWorkerContext(config1);
    WorkItem result1 = worker.onMessage(seed, ctx1);
    JsonNode envelope1 = new ObjectMapper().readTree(result1.asString());
    assertThat(envelope1.get("path").asText()).isEqualTo("/one");

    // Second call supplies a control-plane override pointing at dir2; worker should reload.
    RequestBuilderWorkerConfig config2 = new RequestBuilderWorkerConfig(
        dir2.toString(), "default", true, Map.of());
    WorkerContext ctx2 = new TestWorkerContext(config2);
    WorkItem result2 = worker.onMessage(seed, ctx2);
    JsonNode envelope2 = new ObjectMapper().readTree(result2.asString());
    assertThat(envelope2.get("path").asText()).isEqualTo("/two");
  }

  private static final class TestWorkerContext implements WorkerContext {

    private final RequestBuilderWorkerConfig config;
    private final WorkerInfo info = new WorkerInfo(
        "request-builder",
        WORKER_PROPERTIES.getSwarmId(),
        WORKER_PROPERTIES.getInstanceId(),
        ControlPlaneTestFixtures.workerQueue(WORKER_PROPERTIES.getSwarmId(), "request-builder"),
        null
    );

    private TestWorkerContext(RequestBuilderWorkerConfig config) {
      this.config = config;
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
      if (config != null && type.isAssignableFrom(RequestBuilderWorkerConfig.class)) {
        return type.cast(config);
      }
      return null;
    }

    @Override
    public StatusPublisher statusPublisher() {
      return StatusPublisher.NO_OP;
    }

    @Override
    public org.slf4j.Logger logger() {
      return LoggerFactory.getLogger("test");
    }

    @Override
    public io.micrometer.core.instrument.MeterRegistry meterRegistry() {
      return new SimpleMeterRegistry();
    }

    @Override
    public io.micrometer.observation.ObservationRegistry observationRegistry() {
      return io.micrometer.observation.ObservationRegistry.create();
    }

    @Override
    public ObservabilityContext observabilityContext() {
      return new ObservabilityContext();
    }
  }
}
