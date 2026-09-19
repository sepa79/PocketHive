package io.pockethive.swarmcontroller;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;
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
  private MutableTicker ticker;
  private SwarmStatusFullCoordinator coordinator;

  @BeforeEach
  void setUp() {
    ticker = new MutableTicker();
    coordinator = new SwarmStatusFullCoordinator(
        lifecycle, publisher, initialized::get, ticker);
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
    when(lifecycle.hasWorkerStatusSnapshotsAfter(123L)).thenReturn(false, true);

    coordinator.queueAfterLifecycle(123L);
    verify(publisher, never()).publishFull();

    coordinator.maybePublishPending();
    coordinator.maybePublishPending();

    verify(publisher).publishFull();
  }

  @Test
  void publishesPostLifecycleStatusAfterExplicitTimeout() {
    when(lifecycle.hasWorkerStatusSnapshotsAfter(123L)).thenReturn(false);

    coordinator.queueAfterLifecycle(123L);
    ticker.advance(Duration.ofSeconds(5));
    coordinator.maybePublishPending();

    verify(publisher).publishFull();
  }

  @Test
  void replacingPendingTriggerUsesTheLatestFreshnessCutoff() {
    when(lifecycle.hasWorkerStatusSnapshotsAfter(100L)).thenReturn(false);
    when(lifecycle.hasWorkerStatusSnapshotsAfter(200L)).thenReturn(false, false, true);

    coordinator.queueAfterLifecycle(100L);
    coordinator.queueAfterLifecycle(200L);
    coordinator.maybePublishPending();
    verify(publisher, never()).publishFull();

    coordinator.maybePublishPending();

    verify(publisher).publishFull();
  }

  private static final class MutableTicker implements LongSupplier {

    private long nanos;

    void advance(Duration duration) {
      nanos += duration.toNanos();
    }

    @Override
    public long getAsLong() {
      return nanos;
    }
  }
}
