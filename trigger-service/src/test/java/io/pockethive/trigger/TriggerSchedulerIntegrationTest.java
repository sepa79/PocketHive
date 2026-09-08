package io.pockethive.trigger;

import static org.mockito.Mockito.*;

import io.pockethive.controlplane.ControlPlaneIdentity;
import io.pockethive.worker.sdk.config.*;
import io.pockethive.worker.sdk.input.SchedulerWorkInput;
import io.pockethive.worker.sdk.runtime.*;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class TriggerSchedulerIntegrationTest {
    @Test
    void retainsSingleRequestWhenTwoEnabledUpdatesArriveBetweenTicks() throws Exception {
        var control = mock(WorkerControlPlaneRuntime.class);
        var runtime = mock(WorkerRuntime.class);
        var listener = new AtomicReference<Consumer<WorkerControlPlaneRuntime.WorkerStateSnapshot>>();
        doAnswer(call -> { listener.set(call.getArgument(1)); return null; })
            .when(control).registerStateListener(anyString(), any());
        var definition = new WorkerDefinition("trigger", TriggerWorkerImpl.class, WorkerInputType.SCHEDULER,
            "trigger", WorkIoBindings.none(), TriggerWorkerConfig.class, SchedulerInputProperties.class,
            WorkOutputConfig.class, WorkerOutputType.NONE, null, Set.of());
        var settings = new SchedulerInputProperties();
        settings.setRatePerSec(0);
        settings.setMaxMessages(0);
        settings.setInitialDelayMs(600_000);
        var input = SchedulerWorkInput.<TriggerWorkerConfig>builder()
            .workerDefinition(definition).controlPlaneRuntime(control).workerRuntime(runtime)
            .identity(new ControlPlaneIdentity("swarm", "trigger", "instance"))
            .schedulerState(new TriggerSchedulePolicy()).scheduling(settings).build();
        input.start();
        try {
            listener.get().accept(snapshot(true, true));
            listener.get().accept(snapshot(true, false));
            verifyNoInteractions(runtime); // Config updates must not dispatch or consume quota.
            input.tick(10_000);
            verify(runtime, times(2)).dispatch(eq("trigger"), any());
            input.tick(10_001);
            verifyNoMoreInteractions(runtime);
            listener.get().accept(snapshot(false, true));
            input.tick(10_002);
            verifyNoMoreInteractions(runtime);
            listener.get().accept(snapshot(true, false));
            input.tick(10_003);
            verify(runtime, times(4)).dispatch(eq("trigger"), any());
        } finally {
            input.stop();
        }
    }

    private WorkerControlPlaneRuntime.WorkerStateSnapshot snapshot(boolean enabled, boolean single) {
        var state = mock(WorkerControlPlaneRuntime.WorkerStateSnapshot.class);
        when(state.enabled()).thenReturn(enabled);
        when(state.rawConfig()).thenReturn(Map.of());
        when(state.config(TriggerWorkerConfig.class)).thenReturn(Optional.of(new TriggerWorkerConfig(
            1_000, single, TriggerWorkerConfig.ACTION_SHELL, "true", null, null, null, null)));
        return state;
    }
}
