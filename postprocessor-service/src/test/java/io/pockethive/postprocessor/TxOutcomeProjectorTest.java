package io.pockethive.postprocessor;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import io.pockethive.observability.ObservabilityContext;
import io.pockethive.swarm.model.OutcomeHeaders;
import io.pockethive.worker.sdk.api.StatusPublisher;
import io.pockethive.worker.sdk.api.WorkItem;
import io.pockethive.worker.sdk.api.WorkerContext;
import io.pockethive.worker.sdk.api.WorkerInfo;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;

class TxOutcomeProjectorTest {

  private static final Instant EVENT_TIME = Instant.parse("2026-06-04T18:00:00.123Z");

  @Test
  void projectsDbQueryOutcomeHeadersToClickHouseEvent() {
    WorkerInfo dbInfo = new WorkerInfo("db-query", "swarm-db", "db-1", null, null);
    WorkItem item = WorkItem.text(dbInfo, "{}")
        .header(OutcomeHeaders.CALL_ID, "upstream-call")
        .build()
        .addStep(dbInfo, "{}", Map.of(
            OutcomeHeaders.CALL_ID, "db/select-probe",
            OutcomeHeaders.PROCESSOR_STATUS, 200,
            OutcomeHeaders.PROCESSOR_SUCCESS, true,
            OutcomeHeaders.PROCESSOR_DURATION_MS, 17L,
            OutcomeHeaders.BUSINESS_CODE, "OK",
            OutcomeHeaders.BUSINESS_SUCCESS, true,
            OutcomeHeaders.dimension("adapter"), "POSTGRES",
            OutcomeHeaders.dimension("query_id"), "select-probe",
            OutcomeHeaders.dimension("row_count"), 1
        ));

    Optional<TxOutcomeEvent> projected =
        TxOutcomeProjector.project(item, new StubContext(), EVENT_TIME, true);

    assertThat(projected).isPresent();
    TxOutcomeEvent event = projected.orElseThrow();
    assertThat(event.eventTime()).isEqualTo("2026-06-04 18:00:00.123");
    assertThat(event.swarmId()).isEqualTo("swarm-db");
    assertThat(event.callId()).isEqualTo("db/select-probe");
    assertThat(event.processorStatus()).isEqualTo(200);
    assertThat(event.processorSuccess()).isEqualTo(1);
    assertThat(event.processorDurationMs()).isEqualTo(17L);
    assertThat(event.businessCode()).isEqualTo("OK");
    assertThat(event.businessSuccess()).isEqualTo(1);
    assertThat(event.dimensions()).containsEntry("adapter", "POSTGRES")
        .containsEntry("query_id", "select-probe")
        .containsEntry("row_count", "1");
  }

  private static final class StubContext implements WorkerContext {
    private final MeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final ObservationRegistry observationRegistry = ObservationRegistry.create();
    private final Logger logger = LoggerFactory.getLogger(TxOutcomeProjectorTest.class);

    @Override
    public WorkerInfo info() {
      return new WorkerInfo("postprocessor", "swarm-db", "post-1", "post", null);
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
      return new StatusPublisher() {
        @Override
        public void update(Consumer<MutableStatus> consumer) {
        }

        @Override
        public void emitFull() {
        }
      };
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
      return new ObservabilityContext();
    }
  }
}
