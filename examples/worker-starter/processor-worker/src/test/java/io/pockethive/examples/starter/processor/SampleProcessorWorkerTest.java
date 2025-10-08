package io.pockethive.examples.starter.processor;

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

class SampleProcessorWorkerTest {

  private final SampleProcessorWorker worker = new SampleProcessorWorker();
  private final WorkerContext context = new TestWorkerContext();

  @Test
  void shouldTransformPayload() {
    WorkMessage message = WorkMessage.text("payload").build();

    WorkResult result = worker.onMessage(message, context);

    assertThat(result).isInstanceOf(WorkResult.Message.class);
    WorkMessage outbound = ((WorkResult.Message) result).value();
    assertThat(outbound.asString()).isEqualTo("PAYLOAD");
  }

  private static final class TestWorkerContext implements WorkerContext {

    private final MeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final ObservationRegistry observationRegistry = ObservationRegistry.create();
    private final ObservabilityContext observability = new ObservabilityContext();
    private final Logger logger = LoggerFactory.getLogger(TestWorkerContext.class);

    @Override
    public WorkerInfo info() {
      return new WorkerInfo("processor", "sample-swarm", "processor-1", "ph.processor.in", "ph.processor.out");
    }

    @Override
    public <C> Optional<C> config(Class<C> type) {
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
