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
    tracker.recordStatusSnapshot("gen", "g1", 2_001L);
    tracker.recordEnabled("gen", "g1", true);
    tracker.recordStatusSnapshot("proc", "p1", 2_001L);
    tracker.recordEnabled("proc", "p1", false);

    assertThat(tracker.nonConvergedWorkersSince(2_000L, true))
        .containsExactly(new io.pockethive.swarm.model.lifecycle.Target("proc", "p1"));
  }

  @Test
  void staleMatchingEnablementIsNotConverged() {
    SwarmReadinessTracker tracker = new SwarmReadinessTracker((role, instance, reason) -> { });
    tracker.markReady("gen", "g1");
    tracker.recordEnabled("gen", "g1", true);
    tracker.recordStatusSnapshot("gen", "g1", 1_999L);

    assertThat(tracker.nonConvergedWorkersSince(2_000L, true))
        .containsExactly(new io.pockethive.swarm.model.lifecycle.Target("gen", "g1"));
  }

  @Test
  void hasFreshSnapshotsSinceIsSideEffectFree() {
    SwarmReadinessTracker.StatusRequestCallback callback = mock(SwarmReadinessTracker.StatusRequestCallback.class);
    SwarmReadinessTracker tracker = new SwarmReadinessTracker(callback);

    tracker.markReady("gen", "g1");
    tracker.recordStatusSnapshot("gen", "g1", 1_000L);

    assertThat(tracker.hasFreshSnapshotsSince(2_000L)).isFalse();
    verifyNoInteractions(callback);
  }

  @Test
  void hasFreshSnapshotsSinceIsTrueWhenAllSnapshotsAreFresh() {
    SwarmReadinessTracker tracker = new SwarmReadinessTracker((role, instance, reason) -> {
      throw new AssertionError("callback must not be invoked by hasFreshSnapshotsSince");
    });

    tracker.markReady("gen", "g1");
    tracker.recordStatusSnapshot("gen", "g1", 2_000L);

    assertThat(tracker.hasFreshSnapshotsSince(2_000L)).isTrue();
  }
}
