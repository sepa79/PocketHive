package io.pockethive.swarmcontroller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import io.pockethive.swarm.model.lifecycle.WorkloadState;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SwarmCommandReadinessTest {

  @Mock
  private SwarmLifecycle lifecycle;

  @Test
  void exposesOneSnapshotForLifecycleAndConfigCommandAdmission() {
    AtomicBoolean initialized = new AtomicBoolean(true);
    when(lifecycle.isReadyForWork()).thenReturn(true);
    when(lifecycle.hasPendingConfigUpdates()).thenReturn(false);
    when(lifecycle.getWorkloadState()).thenReturn(WorkloadState.RUNNING);

    SwarmCommandReadinessSnapshot snapshot =
        new SwarmCommandReadiness(lifecycle, initialized::get).snapshot();

    assertThat(snapshot.acceptsStart()).isTrue();
    assertThat(snapshot.acceptsConfig(true)).isTrue();
    assertThat(snapshot.workloadState()).isEqualTo(WorkloadState.RUNNING);
  }

  @Test
  void pendingBootstrapBlocksStartAndConfigButNotStop() {
    when(lifecycle.isReadyForWork()).thenReturn(true);
    when(lifecycle.hasPendingConfigUpdates()).thenReturn(true);
    when(lifecycle.getWorkloadState()).thenReturn(WorkloadState.RUNNING);

    SwarmCommandReadinessSnapshot snapshot =
        new SwarmCommandReadiness(lifecycle, () -> true).snapshot();

    assertThat(snapshot.acceptsStop()).isTrue();
    assertThat(snapshot.acceptsStart()).isFalse();
    assertThat(snapshot.acceptsConfig(true)).isFalse();
  }
  @Test
  void staleWorkersBlockStartButPermitStopAndRunningConfig() {
    var snapshot = new SwarmCommandReadinessSnapshot(true, false, false, WorkloadState.RUNNING);
    assertThat(snapshot.acceptsStart()).isFalse();
    assertThat(snapshot.acceptsStop()).isTrue();
    assertThat(snapshot.acceptsConfig(true)).isTrue();
    assertThat(new SwarmCommandReadinessSnapshot(false, false, false, WorkloadState.RUNNING).acceptsStop())
        .isFalse();
  }

}
