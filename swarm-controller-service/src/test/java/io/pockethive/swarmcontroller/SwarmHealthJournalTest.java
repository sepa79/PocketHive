package io.pockethive.swarmcontroller;

import static io.pockethive.swarmcontroller.SwarmControllerTestProperties.TEST_SWARM_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.pockethive.swarm.model.lifecycle.WorkloadState;
import io.pockethive.swarmcontroller.runtime.SwarmJournal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

@ExtendWith(MockitoExtension.class)
class SwarmHealthJournalTest {

  @Mock
  private SwarmLifecycle lifecycle;

  @Mock
  private SwarmJournal journal;

  private MutableClock clock;
  private SwarmHealthJournal healthJournal;

  @BeforeEach
  void setUp() {
    clock = new MutableClock(Instant.parse("2026-09-01T12:00:00Z"));
    healthJournal = new SwarmHealthJournal(
        lifecycle,
        journal,
        SwarmControllerTestProperties.defaults(),
        "controller-1",
        clock);
  }

  @Test
  void doesNotJournalTheInitialObservation() {
    when(lifecycle.getWorkloadState()).thenReturn(WorkloadState.STOPPED);

    healthJournal.observe(metrics(2, 0));

    verify(journal, never()).append(org.mockito.ArgumentMatchers.any());
  }

  @Test
  void suppressesStartupNoiseThenJournalsOneDegradedAndRecoveredEdge() {
    when(lifecycle.getWorkloadState()).thenReturn(WorkloadState.RUNNING);

    healthJournal.observe(metrics(2, 2));
    clock.advance(Duration.ofSeconds(16));
    healthJournal.observe(metrics(2, 2));
    healthJournal.observe(metrics(2, 1));
    healthJournal.observe(metrics(2, 1));
    healthJournal.observe(metrics(2, 2));

    ArgumentCaptor<SwarmJournal.SwarmJournalEntry> entries =
        ArgumentCaptor.forClass(SwarmJournal.SwarmJournalEntry.class);
    verify(journal, times(2)).append(entries.capture());
    SwarmJournal.SwarmJournalEntry degraded = entries.getAllValues().get(0);
    assertThat(degraded.swarmId()).isEqualTo(TEST_SWARM_ID);
    assertThat(degraded.severity()).isEqualTo("WARN");
    assertThat(degraded.type()).isEqualTo("swarm-health-degraded");
    assertThat(degraded.scope().instance()).isEqualTo("controller-1");
    assertThat(degraded.data())
        .containsEntry("previousState", WorkloadState.RUNNING.name())
        .containsEntry("currentState", "Degraded")
        .containsEntry("desiredWorkers", 2)
        .containsEntry("healthyWorkers", 1);
    SwarmJournal.SwarmJournalEntry recovered = entries.getAllValues().get(1);
    assertThat(recovered.severity()).isEqualTo("INFO");
    assertThat(recovered.type()).isEqualTo("swarm-health-recovered");
    assertThat(recovered.data())
        .containsEntry("previousState", "Degraded")
        .containsEntry("currentState", WorkloadState.RUNNING.name());
  }

  @Test
  void springSelectsTheProductionConstructor() {
    try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
      context.registerBean(SwarmLifecycle.class, () -> lifecycle);
      context.registerBean(SwarmJournal.class, () -> journal);
      context.registerBean(
          io.pockethive.swarmcontroller.config.SwarmControllerProperties.class,
          () -> SwarmControllerTestProperties.defaults());
      context.registerBean("instanceId", String.class, () -> "controller-1");
      context.register(SwarmHealthJournal.class);

      context.refresh();

      assertThat(context.getBean(SwarmHealthJournal.class)).isNotNull();
    }
  }

  private static SwarmMetrics metrics(int desired, int healthy) {
    return new SwarmMetrics(desired, healthy, healthy, healthy, Instant.parse("2026-09-01T12:00:00Z"));
  }

  private static final class MutableClock extends Clock {

    private Instant instant;

    private MutableClock(Instant instant) {
      this.instant = instant;
    }

    void advance(Duration duration) {
      instant = instant.plus(duration);
    }

    @Override
    public ZoneId getZone() {
      return ZoneId.of("UTC");
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return instant;
    }
  }
}
