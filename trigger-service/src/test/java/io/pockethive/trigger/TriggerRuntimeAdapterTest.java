package io.pockethive.trigger;

import io.pockethive.controlplane.ControlPlaneIdentity;
import io.pockethive.worker.sdk.api.WorkMessage;
import io.pockethive.worker.sdk.api.WorkResult;
import io.pockethive.worker.sdk.config.WorkerType;
import io.pockethive.worker.sdk.runtime.WorkerControlPlaneRuntime;
import io.pockethive.worker.sdk.runtime.WorkerDefinition;
import io.pockethive.worker.sdk.runtime.WorkerRegistry;
import io.pockethive.worker.sdk.runtime.WorkerRuntime;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TriggerRuntimeAdapterTest {

  @Mock
  private WorkerRuntime workerRuntime;

  @Mock
  private WorkerRegistry workerRegistry;

  @Mock
  private WorkerControlPlaneRuntime controlPlaneRuntime;

  private TriggerDefaults defaults;
  private WorkerDefinition definition;
  private ControlPlaneIdentity identity;

  @BeforeEach
  void setUp() {
    defaults = new TriggerDefaults();
    defaults.setEnabled(true);
    defaults.setIntervalMs(0L);
    defaults.setActionType("shell");
    defaults.setCommand("echo hi");
    identity = new ControlPlaneIdentity("swarm", "trigger", "inst-1");
    definition = new WorkerDefinition(
        "triggerWorker",
        TriggerWorkerImpl.class,
        WorkerType.GENERATOR,
        "trigger",
        null,
        null,
        TriggerWorkerConfig.class
    );
    when(workerRegistry.all()).thenReturn(List.of(definition));
  }

  @Test
  void tickDispatchesAndEmitsStatus() throws Exception {
    doReturn(WorkResult.none()).when(workerRuntime).dispatch(eq("triggerWorker"), any(WorkMessage.class));
    TriggerRuntimeAdapter adapter = new TriggerRuntimeAdapter(
        workerRuntime,
        workerRegistry,
        controlPlaneRuntime,
        identity,
        defaults,
        Clock.fixed(java.time.Instant.ofEpochMilli(1_000), java.time.ZoneOffset.UTC)
    );

    adapter.emitInitialStatus();
    adapter.tick();
    adapter.emitStatusDelta();

    verify(workerRuntime, times(1)).dispatch(eq("triggerWorker"), any(WorkMessage.class));
    verify(controlPlaneRuntime).emitStatusSnapshot();
    verify(controlPlaneRuntime).emitStatusDelta();
  }

  @Test
  void onControlDelegatesToRuntime() {
    TriggerRuntimeAdapter adapter = new TriggerRuntimeAdapter(
        workerRuntime,
        workerRegistry,
        controlPlaneRuntime,
        identity,
        defaults
    );

    adapter.onControl("{}", "trigger.control", null);
    verify(controlPlaneRuntime).handle("{}", "trigger.control");

    assertThatThrownBy(() -> adapter.onControl(" ", "trigger.control", null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("payload");
    assertThatThrownBy(() -> adapter.onControl("{}", " ", null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("routing key");
  }

  @Test
  void registersStateListenerForTriggerWorker() {
    new TriggerRuntimeAdapter(
        workerRuntime,
        workerRegistry,
        controlPlaneRuntime,
        identity,
        defaults
    );

    ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
    verify(controlPlaneRuntime).registerStateListener(captor.capture(), any());
    assertThat(captor.getValue()).isEqualTo("triggerWorker");
  }
}
