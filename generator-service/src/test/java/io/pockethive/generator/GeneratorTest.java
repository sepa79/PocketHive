package io.pockethive.generator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.pockethive.controlplane.spring.WorkerControlPlaneProperties;
import io.pockethive.worker.sdk.api.StatusPublisher;
import io.pockethive.worker.sdk.api.WorkMessage;
import io.pockethive.worker.sdk.api.WorkResult;
import io.pockethive.worker.sdk.api.WorkerContext;
import io.pockethive.worker.sdk.api.WorkerInfo;
import io.pockethive.worker.sdk.testing.ControlPlaneTestFixtures;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.MessageProperties;

import static org.assertj.core.api.Assertions.assertThat;

class GeneratorTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final WorkerControlPlaneProperties WORKER_PROPERTIES =
      ControlPlaneTestFixtures.workerProperties("swarm", "generator", "instance");
  private static final String IN_QUEUE = WORKER_PROPERTIES.getQueues().get("generator");
  private static final String OUT_QUEUE = WORKER_PROPERTIES.getQueues().get("moderator");

  private GeneratorWorkerProperties properties;
  private GeneratorWorkerImpl worker;

  @BeforeEach
  void setUp() {
    properties = new GeneratorWorkerProperties(new ObjectMapper());
    properties.setEnabled(true);
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
        true,
        10.0,
        false,
        new GeneratorWorkerConfig.Message(
            "/custom",
            "put",
            "{\"value\":42}",
            Map.of("X-Custom", "yes")
        )
    );

    WorkResult result = worker.onMessage(seedMessage(), new TestWorkerContext(config));

    assertThat(result).isInstanceOf(WorkResult.Message.class);
    JsonNode payload = MAPPER.readTree(((WorkResult.Message) result).value().asString());
    assertThat(payload.path("path").asText()).isEqualTo("/custom");
    assertThat(payload.path("method").asText()).isEqualTo("PUT");
    assertThat(payload.path("body").asText()).isEqualTo("{\"value\":42}");
    assertThat(payload.path("headers").path("X-Custom").asText()).isEqualTo("yes");
    assertThat(((WorkResult.Message) result).value().headers())
        .containsEntry("content-type", MessageProperties.CONTENT_TYPE_JSON)
        .containsEntry("x-ph-service", "generator");
  }

  @Test
  void generateFallsBackToDefaultsWhenConfigMissing() throws Exception {
    WorkResult result = worker.onMessage(seedMessage(), new TestWorkerContext(null));

    assertThat(result).isInstanceOf(WorkResult.Message.class);
    JsonNode payload = MAPPER.readTree(((WorkResult.Message) result).value().asString());
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
    public <C> Optional<C> config(Class<C> type) {
      if (config != null && type.isAssignableFrom(GeneratorWorkerConfig.class)) {
        return Optional.of(type.cast(config));
      }
      return Optional.empty();
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

  private static WorkMessage seedMessage() {
    return WorkMessage.builder().build();
  }
}
