package io.pockethive.dataprovider;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.pockethive.controlplane.spring.WorkerControlPlaneProperties;
import io.pockethive.observability.ObservabilityContext;
import io.pockethive.worker.sdk.api.StatusPublisher;
import io.pockethive.worker.sdk.api.WorkItem;
import io.pockethive.worker.sdk.api.WorkerContext;
import io.pockethive.worker.sdk.api.WorkerInfo;
import io.pockethive.worker.sdk.testing.ControlPlaneTestFixtures;
import io.pockethive.worker.sdk.templating.MessageBodyType;
import io.pockethive.worker.sdk.templating.PebbleTemplateRenderer;
import io.pockethive.worker.sdk.templating.TemplateRenderer;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DataProviderWorkerTest {

  private static final WorkerControlPlaneProperties WORKER_PROPERTIES =
      ControlPlaneTestFixtures.workerProperties("swarm", "data-provider", "instance");

  private DataProviderWorkerImpl worker;
  private DataProviderWorkerProperties properties;
  private TemplateRenderer templateRenderer;

  @BeforeEach
  void setUp() {
    properties = new DataProviderWorkerProperties(new ObjectMapper(), WORKER_PROPERTIES);
    templateRenderer = new PebbleTemplateRenderer();
    properties.setConfig(Map.of(
        "template", Map.of(
            "bodyType", "SIMPLE",
            "body", "{{ payload }}",
            "headers", Map.of("X-Default", "yes"))));
    worker = new DataProviderWorkerImpl(properties, templateRenderer);
  }

  @Test
  void enrichesHeadersFromConfigAndSeed() {
    DataProviderWorkerConfig config = new DataProviderWorkerConfig(
        new DataProviderWorkerConfig.Template(
            MessageBodyType.SIMPLE,
            "/",
            "GET",
            "{{ payload }}",
            Map.of("X-Config", "cfg")));
    WorkItem seed = WorkItem.text("payload").header("X-Seed", "seed").build();

    WorkItem result = worker.onMessage(seed, new TestWorkerContext(config));

    assertThat(result.asString()).isEqualTo("payload");
    assertThat(result.headers())
        .containsEntry("X-Seed", "seed")
        .containsEntry("X-Config", "cfg")
        .containsEntry("X-Default", "yes")
        .containsEntry("x-ph-service", "data-provider")
        .containsKey("message-id");
    assertThat(result.headers().get("message-id").toString()).isNotBlank();
    List<?> steps = java.util.stream.StreamSupport.stream(result.steps().spliterator(), false).toList();
    assertThat(steps).hasSizeGreaterThanOrEqualTo(2);
  }

  private static final class TestWorkerContext implements WorkerContext {

    private final DataProviderWorkerConfig config;
    private final WorkerInfo info = new WorkerInfo(
        "data-provider",
        WORKER_PROPERTIES.getSwarmId(),
        WORKER_PROPERTIES.getInstanceId(),
        ControlPlaneTestFixtures.workerQueue(WORKER_PROPERTIES.getSwarmId(), "data-provider"),
        ControlPlaneTestFixtures.workerQueue(WORKER_PROPERTIES.getSwarmId(), "moderator")
    );

    private TestWorkerContext(DataProviderWorkerConfig config) {
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
      if (config != null && type.isAssignableFrom(DataProviderWorkerConfig.class)) {
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
    public ObservabilityContext observabilityContext() {
      return new ObservabilityContext();
    }
  }
}
