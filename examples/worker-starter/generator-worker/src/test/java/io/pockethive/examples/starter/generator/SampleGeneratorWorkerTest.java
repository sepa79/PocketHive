package io.pockethive.examples.starter.generator;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import io.pockethive.observability.ObservabilityContext;
import io.pockethive.worker.sdk.api.StatusPublisher;
import io.pockethive.worker.sdk.api.WorkMessage;
import io.pockethive.worker.sdk.api.WorkResult;
import io.pockethive.worker.sdk.api.WorkerContext;
import io.pockethive.worker.sdk.api.WorkerInfo;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class SampleGeneratorWorkerTest {

  private final SampleGeneratorWorker worker = new SampleGeneratorWorker();

  @Test
  void shouldEmitConfiguredMessage() {
    SampleGeneratorConfig config = new SampleGeneratorConfig(true, 1.0, "demo message");
    WorkerContext context = new TestWorkerContext(config);

    WorkResult result = worker.onMessage(WorkMessage.builder().build(), context);

    assertThat(result).isInstanceOf(WorkResult.Message.class);
    WorkMessage message = ((WorkResult.Message) result).value();
    assertThat(message.asJsonNode().get("message").asText()).isEqualTo("demo message");
  }

  @Test
  void disabledConfigReturnsNone() {
    SampleGeneratorConfig config = new SampleGeneratorConfig(false, 1.0, "demo message");
    WorkerContext context = new TestWorkerContext(config);

    WorkResult result = worker.onMessage(WorkMessage.builder().build(), context);

    assertThat(result).isSameAs(WorkResult.none());
  }

  private static final class TestWorkerContext implements WorkerContext {

    private final SampleGeneratorConfig config;
    private final MeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final ObservationRegistry observationRegistry = ObservationRegistry.create();
    private final ObservabilityContext observability = new ObservabilityContext();
    private final Logger logger = LoggerFactory.getLogger(TestWorkerContext.class);

    private TestWorkerContext(SampleGeneratorConfig config) {
      this.config = config;
    }

    @Override
    public WorkerInfo info() {
      return new WorkerInfo("generator", "sample-swarm", "generator-1", null, "ph.generator.out");
    }

    @Override
    public <C> Optional<C> config(Class<C> type) {
      if (type.isAssignableFrom(SampleGeneratorConfig.class)) {
        return Optional.of(type.cast(config));
      }
      return Optional.empty();
    }

    @Override
    public StatusPublisher statusPublisher() {
      return StatusPublisher.NO_OP;
    }

    @Override
    public Logger logger() {
      return logger;
    }

    @Override
    public MeterRegistry meterRegistry() {
      return meterRegistry;
    }

    @Override
    public ObservationRegistry observationRegistry() {
      return observationRegistry;
    }

    @Override
    public ObservabilityContext observabilityContext() {
      return observability;
    }
  }
}
