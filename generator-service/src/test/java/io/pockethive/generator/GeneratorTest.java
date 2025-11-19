package io.pockethive.generator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.pockethive.controlplane.spring.WorkerControlPlaneProperties;
import io.pockethive.worker.sdk.api.StatusPublisher;
import io.pockethive.worker.sdk.api.WorkItem;
import io.pockethive.worker.sdk.api.WorkerContext;
import io.pockethive.worker.sdk.api.WorkerInfo;
import io.pockethive.worker.sdk.testing.ControlPlaneTestFixtures;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.MessageProperties;

import static org.assertj.core.api.Assertions.assertThat;

class GeneratorTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final WorkerControlPlaneProperties WORKER_PROPERTIES =
      ControlPlaneTestFixtures.workerProperties("swarm", "generator", "instance");
  private static final Map<String, String> WORKER_QUEUES =
      ControlPlaneTestFixtures.workerQueues("swarm");
  private static final String IN_QUEUE = WORKER_QUEUES.get("generator");
  private static final String OUT_QUEUE = WORKER_QUEUES.get("moderator");

  private GeneratorWorkerProperties properties;
  private GeneratorWorkerImpl worker;

  @BeforeEach
  void setUp() {
    properties = new GeneratorWorkerProperties(new ObjectMapper(), WORKER_PROPERTIES);
    Map<String, Object> message = new LinkedHashMap<>();
    message.put("path", "/default");
    message.put("method", "POST");
    message.put("body", "{}");
    message.put("headers", Map.of("X-Test", "true"));
    Map<String, Object> config = new LinkedHashMap<>();
    config.put("ratePerSec", 3.0);
    config.put("singleRequest", false);
    config.put("message", message);
    properties.setConfig(config);
    worker = new GeneratorWorkerImpl(properties);
  }

  @Test
  void generateUsesProvidedConfig() throws Exception {
    GeneratorWorkerConfig config = new GeneratorWorkerConfig(
        10.0,
        false,
        new GeneratorWorkerConfig.Message(
            "/custom",
            "put",
            "{\"value\":42}",
            Map.of("X-Custom", "yes")
        )
    );

    WorkItem result = worker.onMessage(seedMessage(), new TestWorkerContext(config));

    assertThat(result).isNotNull();
    JsonNode payload = MAPPER.readTree(result.asString());
    assertThat(payload.path("path").asText()).isEqualTo("/custom");
    assertThat(payload.path("method").asText()).isEqualTo("PUT");
    assertThat(payload.path("body").asText()).isEqualTo("{\"value\":42}");
    assertThat(payload.path("headers").path("X-Custom").asText()).isEqualTo("yes");
    assertThat(result.headers())
        .containsEntry("content-type", MessageProperties.CONTENT_TYPE_JSON)
        .containsEntry("x-ph-service", "generator");
  }

  @Test
  void generateFallsBackToDefaultsWhenConfigMissing() throws Exception {
    WorkItem result = worker.onMessage(seedMessage(), new TestWorkerContext(null));

    assertThat(result).isNotNull();
    JsonNode payload = MAPPER.readTree(result.asString());
    assertThat(payload.path("path").asText()).isEqualTo("/default");
    assertThat(payload.path("method").asText()).isEqualTo("POST");
    assertThat(payload.path("headers").path("X-Test").asText()).isEqualTo("true");
  }

  private static final class TestWorkerContext implements WorkerContext {

    private final GeneratorWorkerConfig config;
    private final WorkerInfo info = new WorkerInfo(
        "generator",
        WORKER_PROPERTIES.getSwarmId(),
        WORKER_PROPERTIES.getInstanceId(),
        IN_QUEUE,
        OUT_QUEUE
    );

    private TestWorkerContext(GeneratorWorkerConfig config) {
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
      if (config != null && type.isAssignableFrom(GeneratorWorkerConfig.class)) {
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
      return org.slf4j.LoggerFactory.getLogger("test");
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
    public io.pockethive.observability.ObservabilityContext observabilityContext() {
      return new io.pockethive.observability.ObservabilityContext();
    }
  }

  private static WorkItem seedMessage() {
    return WorkItem.builder().build();
  }
}
