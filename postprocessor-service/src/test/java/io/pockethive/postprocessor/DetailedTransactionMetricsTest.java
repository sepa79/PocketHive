package io.pockethive.postprocessor;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import io.pockethive.observability.Hop;
import io.pockethive.observability.ObservabilityContext;
import io.pockethive.worker.sdk.api.StatusPublisher;
import io.pockethive.worker.sdk.api.WorkerContext;
import io.pockethive.worker.sdk.api.WorkerInfo;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static java.util.stream.Collectors.toList;
import static org.assertj.core.api.Assertions.assertThat;

class DetailedTransactionMetricsTest {

  private static final WorkerInfo WORKER_INFO =
      new WorkerInfo("postprocessor", "swarm", "instance", "final", null);

  @Test
  void recordKeepsLatestTransactionsUpToHistorySize() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    DetailedTransactionMetrics metrics =
        new DetailedTransactionMetrics(registry, new StubWorkerContext(registry), 2);

    metrics.record(
        List.of(5L),
        5L,
        List.of(new Hop("generator", "gen-1", Instant.EPOCH, Instant.EPOCH.plusMillis(5))),
        PostProcessorWorkerImpl.ProcessorCallStats.empty());
    metrics.record(
        List.of(3L),
        3L,
        List.of(new Hop("processor", "proc-1", Instant.EPOCH, Instant.EPOCH.plusMillis(3))),
        new PostProcessorWorkerImpl.ProcessorCallStats(2L, true, 200));
    metrics.record(
        List.of(4L),
        4L,
        List.of(new Hop("moderator", "mod-1", Instant.EPOCH, Instant.EPOCH.plusMillis(4))),
        new PostProcessorWorkerImpl.ProcessorCallStats(1L, false, 500));

    List<Gauge> hopGauges = registry.find("ph_transaction_hop_duration_ms").gauges();
    assertThat(hopGauges).hasSize(2);
    assertThat(hopGauges.stream().map(Gauge::value).collect(toList()))
        .containsExactlyInAnyOrder(3.0, 4.0);
    assertThat(hopGauges.stream().map(g -> g.getId().getTag("transaction_seq")).collect(toList()))
        .containsExactlyInAnyOrder("2", "3");

    List<Gauge> totalGauges = registry.find("ph_transaction_total_latency_ms").gauges();
    assertThat(totalGauges).hasSize(2);
    assertThat(totalGauges.stream().map(Gauge::value).collect(toList()))
        .containsExactlyInAnyOrder(3.0, 4.0);

    List<Gauge> processorDuration = registry.find("ph_transaction_processor_duration_ms").gauges();
    assertThat(processorDuration).hasSize(2);
    assertThat(processorDuration.stream().map(Gauge::value).collect(toList()))
        .containsExactlyInAnyOrder(2.0, 1.0);

    List<Gauge> processorSuccess = registry.find("ph_transaction_processor_success").gauges();
    assertThat(processorSuccess).hasSize(2);
    assertThat(processorSuccess.stream().map(Gauge::value).collect(toList()))
        .containsExactlyInAnyOrder(1.0, 0.0);

    List<Gauge> processorStatus = registry.find("ph_transaction_processor_status").gauges();
    assertThat(processorStatus).hasSize(2);
    assertThat(processorStatus.stream().map(Gauge::value).collect(toList()))
        .containsExactlyInAnyOrder(200.0, 500.0);
  }

  private static final class StubWorkerContext implements WorkerContext {
    private final MeterRegistry registry;
    private final Logger logger = LoggerFactory.getLogger(DetailedTransactionMetricsTest.class);
    private final ObservationRegistry observationRegistry = ObservationRegistry.create();
    private final ObservabilityContext observabilityContext = new ObservabilityContext();

    StubWorkerContext(MeterRegistry registry) {
      this.registry = Objects.requireNonNull(registry, "registry");
    }

    @Override
    public WorkerInfo info() {
      return WORKER_INFO;
    }

    @Override
    public <C> Optional<C> config(Class<C> type) {
      return Optional.empty();
    }

    @Override
    public StatusPublisher statusPublisher() {
      return new StatusPublisher() {
        @Override
        public void update(Consumer<MutableStatus> consumer) {
          // no-op for tests
        }

        @Override
        public void emitFull() {
          // no-op for tests
        }
      };
    }

    @Override
    public Logger logger() {
      return logger;
    }

    @Override
    public MeterRegistry meterRegistry() {
      return registry;
    }

    @Override
    public ObservationRegistry observationRegistry() {
      return observationRegistry;
    }

    @Override
    public ObservabilityContext observabilityContext() {
      return observabilityContext;
    }
  }
}
