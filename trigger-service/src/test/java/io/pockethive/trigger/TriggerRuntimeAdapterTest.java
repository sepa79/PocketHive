package io.pockethive.trigger;

import io.pockethive.controlplane.ControlPlaneIdentity;
import io.pockethive.worker.sdk.api.WorkMessage;
import io.pockethive.worker.sdk.api.WorkResult;
import io.pockethive.worker.sdk.autoconfigure.WorkerControlQueueListener;
import io.pockethive.worker.sdk.config.WorkerInputType;
import io.pockethive.worker.sdk.input.WorkInput;
import io.pockethive.worker.sdk.input.WorkInputRegistry;
import io.pockethive.worker.sdk.input.SchedulerWorkInput;
import io.pockethive.worker.sdk.runtime.WorkerControlPlaneRuntime;
import io.pockethive.worker.sdk.runtime.WorkerDefinition;
import io.pockethive.worker.sdk.runtime.WorkerRegistry;
import io.pockethive.worker.sdk.runtime.WorkerRuntime;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
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

  @Mock
  private WorkInputRegistry workInputRegistry;

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
        WorkerInputType.SCHEDULER,
        "trigger",
        null,
        null,
        null,
        TriggerWorkerConfig.class
    );
  }

  @Test
  void tickDispatchesAndEmitsStatus() throws Exception {
    when(workerRegistry.streamByRoleAndInput("trigger", WorkerInputType.SCHEDULER))
        .thenAnswer(invocation -> Stream.of(definition));
    doReturn(WorkResult.none()).when(workerRuntime).dispatch(eq("triggerWorker"), any(WorkMessage.class));
    TriggerRuntimeAdapter adapter = new TriggerRuntimeAdapter(
        workerRuntime,
        workerRegistry,
        controlPlaneRuntime,
        identity,
        defaults,
        Clock.fixed(java.time.Instant.ofEpochMilli(1_000), java.time.ZoneOffset.UTC),
        workInputRegistry
    );
    adapter.start();
    adapter.tick();

    ArgumentCaptor<WorkMessage> workMessageCaptor = ArgumentCaptor.forClass(WorkMessage.class);
    verify(workerRuntime, times(1)).dispatch(eq("triggerWorker"), workMessageCaptor.capture());
    WorkMessage dispatched = workMessageCaptor.getValue();
    assertThat(dispatched.headers()).containsEntry("swarmId", identity.swarmId());
    assertThat(dispatched.headers()).containsEntry("instanceId", identity.instanceId());
    verify(controlPlaneRuntime).emitStatusSnapshot();
    ArgumentCaptor<WorkInput> workInputCaptor = ArgumentCaptor.forClass(WorkInput.class);
    verify(workInputRegistry).register(eq(definition), workInputCaptor.capture());
    assertThat(workInputCaptor.getValue()).isInstanceOf(SchedulerWorkInput.class);
  }

  @Test
  void controlQueueListenerDelegatesToRuntime() {
    WorkerControlQueueListener listener = new WorkerControlQueueListener(controlPlaneRuntime);

    listener.onControl("{}", "trigger.control", null);
    verify(controlPlaneRuntime).handle("{}", "trigger.control");

    assertThatThrownBy(() -> listener.onControl(" ", "trigger.control", null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("payload");
    assertThatThrownBy(() -> listener.onControl("{}", " ", null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("routing key");
  }

  @Test
  void registersStateListenerForTriggerWorker() {
    when(workerRegistry.streamByRoleAndInput("trigger", WorkerInputType.SCHEDULER))
        .thenAnswer(invocation -> Stream.of(definition));
    TriggerRuntimeAdapter adapter = new TriggerRuntimeAdapter(
        workerRuntime,
        workerRegistry,
        controlPlaneRuntime,
        identity,
        defaults,
        workInputRegistry
    );

    adapter.start();
    ArgumentCaptor<WorkInput> workInputCaptor = ArgumentCaptor.forClass(WorkInput.class);
    verify(workInputRegistry).register(eq(definition), workInputCaptor.capture());
    assertThat(workInputCaptor.getValue()).isInstanceOf(SchedulerWorkInput.class);

    ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
    InOrder inOrder = Mockito.inOrder(controlPlaneRuntime);
    inOrder.verify(controlPlaneRuntime).registerDefaultConfig(eq("triggerWorker"), any());
    inOrder.verify(controlPlaneRuntime).registerStateListener(captor.capture(), any());
    assertThat(captor.getValue()).isEqualTo("triggerWorker");
  }

  @Test
  void singleRequestRespectsIntervalBeforeNextDispatch() throws Exception {
    when(workerRegistry.streamByRoleAndInput("trigger", WorkerInputType.SCHEDULER))
        .thenAnswer(invocation -> Stream.of(definition));
    MutableClock clock = new MutableClock(10_000L);
    defaults.setIntervalMs(60_000L);
    defaults.setEnabled(true);
    doReturn(WorkResult.none()).when(workerRuntime).dispatch(eq("triggerWorker"), any(WorkMessage.class));

    AtomicReference<Consumer<WorkerControlPlaneRuntime.WorkerStateSnapshot>> listenerRef =
        new AtomicReference<>();
    doAnswer(invocation -> {
      listenerRef.set(invocation.getArgument(1));
      return null;
    }).when(controlPlaneRuntime).registerStateListener(eq("triggerWorker"), any());

    TriggerRuntimeAdapter adapter = new TriggerRuntimeAdapter(
        workerRuntime,
        workerRegistry,
        controlPlaneRuntime,
        identity,
        defaults,
        clock,
        workInputRegistry
    );
    adapter.start();
    adapter.start();

    Consumer<WorkerControlPlaneRuntime.WorkerStateSnapshot> listener = listenerRef.get();
    assertThat(listener).as("control-plane listener should be registered").isNotNull();

    TriggerWorkerConfig singleRequestConfig = new TriggerWorkerConfig(
        true,
        60_000L,
        true,
        "shell",
        "echo hi",
        "",
        "GET",
        "",
        Map.of()
    );

    WorkerControlPlaneRuntime.WorkerStateSnapshot snapshot =
        mock(WorkerControlPlaneRuntime.WorkerStateSnapshot.class);
    when(snapshot.config(TriggerWorkerConfig.class)).thenReturn(Optional.of(singleRequestConfig));
    when(snapshot.enabled()).thenReturn(Optional.of(true));

    listener.accept(snapshot);

    adapter.tick();
    verify(workerRuntime, times(1)).dispatch(eq("triggerWorker"), any(WorkMessage.class));

    clock.advance(1_000L);
    adapter.tick();
    verify(workerRuntime, times(1)).dispatch(eq("triggerWorker"), any(WorkMessage.class));
  }

  private static final class MutableClock extends Clock {

    private final AtomicLong millis;
    private final ZoneId zone;

    private MutableClock(long initialMillis) {
      this(initialMillis, ZoneOffset.UTC);
    }

    private MutableClock(long initialMillis, ZoneId zone) {
      this.millis = new AtomicLong(initialMillis);
      this.zone = zone;
    }

    @Override
    public ZoneId getZone() {
      return zone;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return new MutableClock(millis.get(), zone);
    }

    @Override
    public Instant instant() {
      return Instant.ofEpochMilli(millis.get());
    }

    void advance(long deltaMillis) {
      millis.addAndGet(deltaMillis);
    }
  }
}
