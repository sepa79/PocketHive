package io.pockethive.worker.sdk.input;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import io.pockethive.controlplane.ControlPlaneIdentity;
import io.pockethive.work.api.WorkItem;
import io.pockethive.work.config.WorkerInputType;
import io.pockethive.work.config.WorkerOutputType;
import io.pockethive.worker.sdk.config.SchedulerInputProperties;
import io.pockethive.worker.sdk.config.WorkOutputConfig;
import io.pockethive.worker.sdk.runtime.WorkIoBindings;
import io.pockethive.worker.sdk.runtime.WorkerControlPlaneRuntime;
import io.pockethive.worker.sdk.runtime.WorkerDefinition;
import io.pockethive.worker.sdk.runtime.WorkerRuntime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class SchedulerWorkInputTest {
    @Test
    void finiteRunAppliesValidatedControlsWithoutPartialChanges() throws Exception {
        var control = mock(WorkerControlPlaneRuntime.class);
        var runtime = mock(WorkerRuntime.class);
        var dispatched = new ArrayList<WorkItem>();
        doAnswer(call -> { dispatched.add(call.getArgument(1)); return null; })
            .when(runtime).dispatch(eq("generator"), any());
        var listener = new AtomicReference<Consumer<WorkerControlPlaneRuntime.WorkerStateSnapshot>>();
        doAnswer(call -> { listener.set(call.getArgument(1)); return null; })
            .when(control).registerStateListener(anyString(), any());
        var definition = new WorkerDefinition("generator", Object.class, WorkerInputType.SCHEDULER,
            "generator", WorkIoBindings.none(), Object.class, SchedulerInputProperties.class,
            WorkOutputConfig.class, WorkerOutputType.NONE, null, Set.of());
        var settings = new SchedulerInputProperties();
        settings.setRatePerSec(5);
        settings.setMaxMessages(2);
        settings.setInitialDelayMs(600_000);
        var input = SchedulerWorkInput.builder().workerDefinition(definition).controlPlaneRuntime(control)
            .workerRuntime(runtime).identity(new ControlPlaneIdentity("swarm", "generator", "instance"))
            .schedulerState(new RateSchedulePolicy()).scheduling(settings).build();
        input.start();
        try {
            listener.get().accept(snapshot(Map.of()));
            input.tick(0);
            input.tick(1_000);
            assertThat(dispatched).extracting(item -> item.headers().get("x-ph-scheduler-remaining"))
                .containsExactly(1L, 0L);

            listener.get().accept(snapshot(Map.of("maxMessages", "3")));
            input.tick(2_000);
            input.tick(3_000);
            assertThat(dispatched).extracting(item -> item.headers().get("x-ph-scheduler-remaining"))
                .containsExactly(1L, 0L, 2L, 1L, 0L);

            for (Object invalid : new Object[]{null, -1, 3.5, "9223372036854775808"}) {
                var patch = new LinkedHashMap<String, Object>();
                patch.put("ratePerSec", 99);
                patch.put("maxMessages", invalid);
                assertThatThrownBy(() -> listener.get().accept(snapshot(patch)))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("inputs.scheduler.maxMessages");
                assertThat(settings.ratePerSec()).isEqualTo(5.0);
            }
            input.tick(4_000);
            assertThat(dispatched).hasSize(5);

            for (Object invalid : new Object[]{null, "true", "false", 1, Map.of(), "{{ true }}"}) {
                var patch = new LinkedHashMap<String, Object>();
                patch.put("ratePerSec", 99);
                patch.put("maxMessages", 4);
                patch.put("reset", invalid);
                assertThatThrownBy(() -> listener.get().accept(snapshot(patch)))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("inputs.scheduler.reset");
                assertThat(settings.ratePerSec()).isEqualTo(5.0);
            }
            listener.get().accept(snapshot(Map.of()));
            input.tick(4_500);
            assertThat(dispatched).hasSize(5);

            listener.get().accept(snapshot(Map.of("reset", false)));
            input.tick(5_000);
            assertThat(dispatched).hasSize(5);
            listener.get().accept(snapshot(Map.of("reset", true)));
            input.tick(6_000);
            assertThat(dispatched).hasSize(8);
            assertThat(dispatched.subList(5, 8)).extracting(item -> item.headers().get("x-ph-scheduler-remaining"))
                .containsExactly(2L, 1L, 0L);

            listener.get().accept(snapshot(Map.of("maxMessages", "9223372036854775807")));
            input.tick(7_000);
            assertThat(dispatched).hasSize(13);
            assertThat(dispatched.get(8).headers()).containsEntry("x-ph-scheduler-remaining", Long.MAX_VALUE - 1);

            listener.get().accept(snapshot(Map.of("maxMessages", 0)));
            input.tick(8_000);
            input.tick(9_000);
            assertThat(dispatched).hasSize(23);
            assertThat(dispatched.subList(13, 23))
                .allSatisfy(item -> assertThat(item.headers()).doesNotContainKey("x-ph-scheduler-remaining"));
        } finally {
            input.stop();
        }
    }

    private WorkerControlPlaneRuntime.WorkerStateSnapshot snapshot(Map<String, Object> settings) {
        var snapshot = mock(WorkerControlPlaneRuntime.WorkerStateSnapshot.class);
        when(snapshot.enabled()).thenReturn(true);
        when(snapshot.rawConfig()).thenReturn(Map.of("inputs", Map.of("scheduler", settings)));
        when(snapshot.config(Object.class)).thenReturn(Optional.empty());
        return snapshot;
    }
}
