package io.pockethive.swarmcontroller;

import org.junit.jupiter.api.Test;
import java.util.List;
import io.pockethive.swarm.model.lifecycle.Target;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class SwarmReadinessTrackerTest {

  @Test
  void reportsWorkersWithoutFreshMatchingEnablementEvidence() {
    SwarmReadinessTracker tracker = new SwarmReadinessTracker((role, instance, reason) -> { });
    tracker.markReady("gen", "g1");
    tracker.markReady("proc", "p1");
    long beforeCommand = tracker.statusObservationRevision();
    tracker.recordStatusSnapshot("gen", "g1", true);
    tracker.recordStatusSnapshot("proc", "p1", false);

    assertThat(tracker.nonConvergedWorkersAfter(beforeCommand, true, List.of(new Target("gen", "g1"), new Target("proc", "p1"))))
        .containsExactly(new io.pockethive.swarm.model.lifecycle.Target("proc", "p1"));
  }

  @Test
  void preCommandMatchingEnablementIsNotConverged() {
    SwarmReadinessTracker tracker = new SwarmReadinessTracker((role, instance, reason) -> { });
    tracker.markReady("gen", "g1");
    tracker.recordStatusSnapshot("gen", "g1", true);
    long beforeCommand = tracker.statusObservationRevision();

    assertThat(tracker.nonConvergedWorkersAfter(beforeCommand, true, List.of(new Target("gen", "g1"))))
        .containsExactly(new io.pockethive.swarm.model.lifecycle.Target("gen", "g1"));
  }

  @Test
  void deltaEnablementCannotReplacePostCommandFullSnapshotEvidence() {
    SwarmReadinessTracker tracker = new SwarmReadinessTracker((role, instance, reason) -> { });
    tracker.markReady("gen", "g1");
    tracker.recordStatusSnapshot("gen", "g1", true);
    long beforeCommand = tracker.statusObservationRevision();

    tracker.recordEnabled("gen", "g1", false);

    assertThat(tracker.nonConvergedWorkersAfter(beforeCommand, false, List.of(new Target("gen", "g1"))))
        .containsExactly(new io.pockethive.swarm.model.lifecycle.Target("gen", "g1"));
  }

  @Test
  void snapshotRevisionCheckIsSideEffectFree() {
    WorkerStatusRequestCallback callback = mock(WorkerStatusRequestCallback.class);
    SwarmReadinessTracker tracker = new SwarmReadinessTracker(callback);

    tracker.markReady("gen", "g1");
    tracker.recordStatusSnapshot("gen", "g1", false);
    long beforeCommand = tracker.statusObservationRevision();

    assertThat(tracker.hasSnapshotsAfter(beforeCommand)).isFalse();
    verifyNoInteractions(callback);
  }

  @Test
  void snapshotRevisionCheckIsTrueWhenAllSnapshotsArePostCommand() {
    SwarmReadinessTracker tracker = new SwarmReadinessTracker((role, instance, reason) -> {
      throw new AssertionError("callback must not be invoked by snapshot revision checks");
    });

    tracker.markReady("gen", "g1");
    long beforeCommand = tracker.statusObservationRevision();
    tracker.recordStatusSnapshot("gen", "g1", false);

    assertThat(tracker.hasSnapshotsAfter(beforeCommand)).isTrue();
  }
  @Test
  void readinessMetricsAndWorkerListShareFreshnessBoundaryAndRecovery() {
    java.util.concurrent.atomic.AtomicLong now = new java.util.concurrent.atomic.AtomicLong(20_000);
    java.time.Clock clock = mock(java.time.Clock.class);
    org.mockito.Mockito.when(clock.millis()).thenAnswer(invocation -> now.get());
    WorkerStatusRequestCallback callback = mock(WorkerStatusRequestCallback.class);
    SwarmReadinessTracker tracker = new SwarmReadinessTracker(callback, clock);
    SwarmWorkersAggregator workers = new SwarmWorkersAggregator();
    tracker.registerExpected("gen");
    tracker.recordHeartbeat("gen", "g1", now.get());
    tracker.recordStatusSnapshot("gen", "g1", true);
    tracker.markReady("gen", "g1");
    workers.updateFromWorkerStatus("gen", "g1", new com.fasterxml.jackson.databind.ObjectMapper()
        .createObjectNode().put("tps", 3), null);

    now.addAndGet(15_000);
    assertThat(tracker.isReadyForWork()).isTrue();
    assertThat(tracker.metrics().healthy()).isEqualTo(1);
    assertThat(tracker.metrics().running()).isEqualTo(1);
    assertThat(workers.snapshot(tracker.workerObservations())).singleElement().satisfies(worker ->
        assertThat(worker).containsEntry("stale", false).containsEntry("enabled", true)
            .containsEntry("lastSeenAt", java.time.Instant.ofEpochMilli(20_000).toString()));

    now.incrementAndGet();
    assertThat(tracker.isReadyForWork()).isFalse();
    assertThat(tracker.metrics().healthy()).isZero();
    assertThat(tracker.metrics().running()).isZero();
    assertThat(tracker.metrics().enabled()).isEqualTo(1);
    assertThat(workers.snapshot(tracker.workerObservations())).singleElement().satisfies(worker ->
        assertThat(worker).containsEntry("stale", true).containsEntry("enabled", true));
    org.mockito.Mockito.verify(callback).requestStatus("gen", "g1", "stale-heartbeat");

    long revision = tracker.statusObservationRevision();
    tracker.recordHeartbeat("gen", "g1", now.get());
    tracker.recordEnabled("gen", "g1", false);
    assertThat(tracker.isReadyForWork()).isTrue();
    assertThat(tracker.metrics().healthy()).isEqualTo(1);
    assertThat(tracker.metrics().running()).isZero();
    assertThat(workers.snapshot(tracker.workerObservations())).singleElement().satisfies(worker ->
        assertThat(worker).containsEntry("stale", false).containsEntry("enabled", false)
            .containsEntry("lastSeenAt", java.time.Instant.ofEpochMilli(now.get()).toString()));
    assertThat(tracker.hasSnapshotsAfter(revision)).isFalse();
    assertThat(tracker.nonConvergedWorkersAfter(revision, false, List.of(new Target("gen", "g1")))).hasSize(1);
    tracker.recordStatusSnapshot("gen", "g1", false);
    assertThat(tracker.nonConvergedWorkersAfter(revision, false, List.of(new Target("gen", "g1")))).isEmpty();

    tracker.reset();
    assertThat(tracker.workerObservations()).isEmpty();
    assertThat(tracker.metrics().healthy()).isZero();
    assertThat(workers.snapshot(tracker.workerObservations())).isEmpty();
  }

  @Test
  void missingHeartbeatIsNotHealthyAndReadProjectionDoesNotRequestStatus() {
    WorkerStatusRequestCallback callback = mock(WorkerStatusRequestCallback.class);
    SwarmReadinessTracker tracker = new SwarmReadinessTracker(callback);
    tracker.registerExpected("gen");
    tracker.recordEnabled("gen", "g1", true);
    assertThat(tracker.workerObservations()).isEmpty();
    assertThat(tracker.metrics().healthy()).isZero();
    verifyNoInteractions(callback);
    assertThat(tracker.markReady("gen", "g1")).isFalse();
    org.mockito.Mockito.verify(callback).requestStatus("gen", "g1", "missing-heartbeat");
  }

  @Test
  void stopRequiresEvidenceFromExpectedWorkerThatNeverBecameReady() {
    var tracker = new SwarmReadinessTracker((role, instance, reason) -> { });
    var worker = new Target("gen", "never-ready");
    long revision = tracker.statusObservationRevision();
    assertThat(tracker.nonConvergedWorkersAfter(revision, false, List.of(worker))).containsExactly(worker);
    tracker.recordStatusSnapshot(worker.role(), worker.instance(), true);
    assertThat(tracker.nonConvergedWorkersAfter(revision, false, List.of(worker))).containsExactly(worker);
    tracker.recordStatusSnapshot(worker.role(), worker.instance(), false);
    assertThat(tracker.nonConvergedWorkersAfter(revision, false, List.of(worker))).isEmpty();
  }

}
