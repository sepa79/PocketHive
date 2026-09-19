package io.pockethive.swarmcontroller;

import org.junit.jupiter.api.Test;

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

    assertThat(tracker.nonConvergedWorkersAfter(beforeCommand, true))
        .containsExactly(new io.pockethive.swarm.model.lifecycle.Target("proc", "p1"));
  }

  @Test
  void preCommandMatchingEnablementIsNotConverged() {
    SwarmReadinessTracker tracker = new SwarmReadinessTracker((role, instance, reason) -> { });
    tracker.markReady("gen", "g1");
    tracker.recordStatusSnapshot("gen", "g1", true);
    long beforeCommand = tracker.statusObservationRevision();

    assertThat(tracker.nonConvergedWorkersAfter(beforeCommand, true))
        .containsExactly(new io.pockethive.swarm.model.lifecycle.Target("gen", "g1"));
  }

  @Test
  void deltaEnablementCannotReplacePostCommandFullSnapshotEvidence() {
    SwarmReadinessTracker tracker = new SwarmReadinessTracker((role, instance, reason) -> { });
    tracker.markReady("gen", "g1");
    tracker.recordStatusSnapshot("gen", "g1", true);
    long beforeCommand = tracker.statusObservationRevision();

    tracker.recordEnabled("gen", "g1", false);

    assertThat(tracker.nonConvergedWorkersAfter(beforeCommand, false))
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
}
