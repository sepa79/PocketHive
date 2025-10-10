package io.pockethive.generator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.pockethive.worker.sdk.api.StatusPublisher;
import io.pockethive.worker.sdk.api.WorkResult;
import io.pockethive.worker.sdk.api.WorkerContext;
import io.pockethive.worker.sdk.api.WorkerInfo;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.MessageProperties;

import static org.assertj.core.api.Assertions.assertThat;

class GeneratorTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private GeneratorDefaults defaults;
  private GeneratorQueuesProperties queues;
  private GeneratorWorkerImpl worker;

  @BeforeEach
  void setUp() {
    MessageConfig messageConfig = new MessageConfig();
    messageConfig.setPath("/default");
    messageConfig.setMethod("POST");
    messageConfig.setBody("{}");
    messageConfig.setHeaders(Map.of("X-Test", "true"));
    defaults = new GeneratorDefaults(messageConfig);
    defaults.setRatePerSec(3.0);
    defaults.setEnabled(true);
    queues = new GeneratorQueuesProperties();
    worker = new GeneratorWorkerImpl(defaults, queues);
  }

  @Test
  void generateUsesProvidedConfig() throws Exception {
    GeneratorWorkerConfig config = new GeneratorWorkerConfig(
        true,
        10.0,
        false,
        "/custom",
        "put",
        "{\"value\":42}",
        Map.of("X-Custom", "yes")
    );

    TestWorkerContext context = new TestWorkerContext(config);
    queues.setGenQueue("ph.custom.gen");

    WorkResult result = worker.generate(context);

    assertThat(result).isInstanceOf(WorkResult.Message.class);
    JsonNode payload = MAPPER.readTree(((WorkResult.Message) result).value().asString());
    assertThat(payload.path("path").asText()).isEqualTo("/custom");
    assertThat(payload.path("method").asText()).isEqualTo("PUT");
    assertThat(payload.path("body").asText()).isEqualTo("{\"value\":42}");
    assertThat(payload.path("headers").path("X-Custom").asText()).isEqualTo("yes");
    assertThat(((WorkResult.Message) result).value().headers())
        .containsEntry("content-type", MessageProperties.CONTENT_TYPE_JSON)
        .containsEntry("x-ph-service", "generator");
    assertThat(context.workOutRoute()).isEqualTo("ph.custom.gen");
  }

  @Test
  void generateFallsBackToDefaultsWhenConfigMissing() throws Exception {
    WorkResult result = worker.generate(new TestWorkerContext(null));

    assertThat(result).isInstanceOf(WorkResult.Message.class);
    JsonNode payload = MAPPER.readTree(((WorkResult.Message) result).value().asString());
    assertThat(payload.path("path").asText()).isEqualTo("/default");
    assertThat(payload.path("method").asText()).isEqualTo("POST");
    assertThat(payload.path("headers").path("X-Test").asText()).isEqualTo("true");
  }

  private static final class TestWorkerContext implements WorkerContext {

    private final GeneratorWorkerConfig config;
    private final WorkerInfo info = new WorkerInfo("generator", "swarm", "instance", "in", "out");
    private final CapturingStatusPublisher statusPublisher = new CapturingStatusPublisher();

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
      return statusPublisher;
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

    String workOutRoute() {
      return statusPublisher.workOutRoute;
    }
  }

  private static final class CapturingStatusPublisher implements StatusPublisher {

    private String workOutRoute;

    @Override
    public StatusPublisher workOut(String route) {
      this.workOutRoute = route;
      return this;
    }

    @Override
    public void update(java.util.function.Consumer<MutableStatus> consumer) {
      // no-op for tests
    }
  }
}
