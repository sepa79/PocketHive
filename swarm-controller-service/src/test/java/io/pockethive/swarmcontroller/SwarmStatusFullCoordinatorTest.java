package io.pockethive.swarmcontroller;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SwarmStatusFullCoordinatorTest {

  @Mock
  private SwarmLifecycle lifecycle;

  @Mock
  private SwarmControllerStatusPublisher publisher;

  private final AtomicBoolean initialized = new AtomicBoolean(true);
  private MutableClock clock;
  private SwarmStatusFullCoordinator coordinator;

  @BeforeEach
  void setUp() {
    clock = new MutableClock(Instant.parse("2026-09-01T12:00:00Z"));
    coordinator = new SwarmStatusFullCoordinator(
        lifecycle, publisher, initialized::get, clock);
  }

  @Test
  void publishesStartupReadyStatusAtMostOnce() {
    when(lifecycle.isReadyForWork()).thenReturn(true);
    when(lifecycle.hasPendingConfigUpdates()).thenReturn(false);

    coordinator.maybePublishStartupReady();
    coordinator.maybePublishStartupReady();

    verify(publisher).publishFull();
  }

  @Test
  void keepsPostLifecycleStatusPendingUntilWorkerSnapshotsAreFresh() {
    when(lifecycle.hasFreshWorkerStatusSnapshotsSince(123L)).thenReturn(false, true);

    coordinator.queueAfterLifecycle(123L);
    verify(publisher, never()).publishFull();

    coordinator.maybePublishPending();
    coordinator.maybePublishPending();

    verify(publisher).publishFull();
  }

  @Test
  void publishesPostLifecycleStatusAfterExplicitTimeout() {
    when(lifecycle.hasFreshWorkerStatusSnapshotsSince(123L)).thenReturn(false);

    coordinator.queueAfterLifecycle(123L);
    clock.advance(Duration.ofSeconds(5));
    coordinator.maybePublishPending();

    verify(publisher).publishFull();
  }

  @Test
  void replacingPendingTriggerUsesTheLatestFreshnessCutoff() {
    when(lifecycle.hasFreshWorkerStatusSnapshotsSince(100L)).thenReturn(false);
    when(lifecycle.hasFreshWorkerStatusSnapshotsSince(200L)).thenReturn(false, false, true);

    coordinator.queueAfterLifecycle(100L);
    coordinator.queueAfterLifecycle(200L);
    coordinator.maybePublishPending();
    verify(publisher, never()).publishFull();

    coordinator.maybePublishPending();

    verify(publisher).publishFull();
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
