package io.pockethive.worker.sdk.input.csv;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;

import io.pockethive.controlplane.ControlPlaneIdentity;
import io.pockethive.work.config.binding.WorkInputConfig;
import io.pockethive.work.config.binding.WorkOutputConfig;
import io.pockethive.work.config.WorkerInputType;
import io.pockethive.work.config.WorkerOutputType;
import io.pockethive.worker.sdk.runtime.WorkIoBindings;
import io.pockethive.worker.sdk.runtime.WorkerControlPlaneRuntime;
import io.pockethive.worker.sdk.runtime.WorkerDefinition;
import io.pockethive.worker.sdk.runtime.WorkerRuntime;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class CsvDataSetWorkInputTest {

    @org.junit.jupiter.api.io.TempDir java.nio.file.Path directory;

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void readsResolvedFormatAndKeepsCursorAndSettingsAfterRejectedUpdate(boolean rotate) throws Exception {
        var file = directory.resolve("data.csv");
        java.nio.file.Files.writeString(file, "name|value\nfirst|1\nsecond|2\n");
        var properties = baseProperties();
        properties.setFilePath(file.toString());
        properties.setDelimiter("\\|");
        properties.setStartupDelaySeconds(600);
        properties.setRotate(rotate);
        var items = new java.util.ArrayList<io.pockethive.work.api.WorkItem>();
        var control = mock(WorkerControlPlaneRuntime.class);
        var state = mock(WorkerControlPlaneRuntime.WorkerStateSnapshot.class);
        when(state.enabled()).thenReturn(true);
        when(state.rawConfig()).thenReturn(java.util.Map.of());
        var listener = new java.util.concurrent.atomic.AtomicReference<
            java.util.function.Consumer<WorkerControlPlaneRuntime.WorkerStateSnapshot>>();
        doAnswer(call -> {
            listener.set(call.getArgument(1)); listener.get().accept(state); return null;
        }).when(control).registerStateListener(any(), any());
        var input = new CsvDataSetWorkInput(definition(), control, (name, item) -> { items.add(item); return item; },
            new ControlPlaneIdentity("swarm-1", "role", "instance-1"), properties);
        try {
            input.start(); input.tick();
            assertThat(items).extracting(io.pockethive.work.api.WorkItem::asString)
                .containsExactly("{\"name\":\"first\",\"value\":\"1\"}");
            assertThatThrownBy(() -> input.applyRawConfigOverrides(java.util.Map.of("inputs", java.util.Map.of("csv",
                java.util.Map.of("ratePerSec", 20, "rotate", "yes", "filePath", "/wrong.csv")))))
                .hasMessageContaining("inputs.csv.rotate");
            when(state.enabled()).thenReturn(false); listener.get().accept(state); input.tick();
            assertThat(items).hasSize(1);
            when(state.enabled()).thenReturn(true); listener.get().accept(state); input.tick();
            assertThat(items).extracting(io.pockethive.work.api.WorkItem::asString)
                .containsExactly("{\"name\":\"first\",\"value\":\"1\"}", "{\"name\":\"second\",\"value\":\"2\"}");
            input.tick();
            assertThat(items).hasSize(rotate ? 3 : 2);
            if (rotate) assertThat(items.getLast().asString()).isEqualTo(items.getFirst().asString());
        } finally { input.stop(); }
    }

    @Test
    void reloadsOnRestartWithoutResettingCursorOrRetryingFailedDispatch() throws Exception {
        var file = directory.resolve("restart.csv");
        java.nio.file.Files.writeString(file, "name\nfirst\nsecond\nthird\n");
        var properties = baseProperties();
        properties.setFilePath(file.toString());
        properties.setStartupDelaySeconds(600);
        var items = new java.util.ArrayList<io.pockethive.work.api.WorkItem>();
        var control = mock(WorkerControlPlaneRuntime.class);
        var state = mock(WorkerControlPlaneRuntime.WorkerStateSnapshot.class);
        when(state.enabled()).thenReturn(true);
        when(state.rawConfig()).thenReturn(java.util.Map.of());
        doAnswer(call -> {
            java.util.function.Consumer<WorkerControlPlaneRuntime.WorkerStateSnapshot> listener = call.getArgument(1);
            listener.accept(state);
            return null;
        }).when(control).registerStateListener(any(), any());
        var input = new CsvDataSetWorkInput(definition(), control, (name, item) -> {
            items.add(item);
            if (items.size() == 1) throw new IllegalStateException("template failure");
            return item;
        }, new ControlPlaneIdentity("swarm-1", "role", "instance-1"), properties);
        try {
            input.start();
            assertThatCode(input::tick).doesNotThrowAnyException();
            input.stop();
            java.nio.file.Files.writeString(file, "name\nchanged-first\nchanged-second\nchanged-third\n");
            input.start();
            input.tick();
            input.tick();
            input.tick();
            assertThat(items).extracting(io.pockethive.work.api.WorkItem::asString)
                .containsExactly("{\"name\":\"first\"}", "{\"name\":\"changed-second\"}",
                    "{\"name\":\"changed-third\"}");
            assertThat(items).extracting(item -> item.headers().get("x-ph-csv-row"))
                .containsExactly("1", "2", "3");
            assertThat(items).extracting(item -> item.headers().get("x-ph-csv-remaining"))
                .containsExactly(2L, 1L, 0L);
            assertThat(items).allSatisfy(item -> assertThat(item.headers())
                .containsEntry("x-ph-csv-file", file.toString())
                .containsEntry("swarmId", "swarm-1").containsEntry("instanceId", "instance-1"));
        } finally { input.stop(); }
    }

    @Test
    void validatesDirectRuntimeConfigWithHighRateAndNoBusinessUpperBound() {
        CsvDataSetInputProperties properties = baseProperties();
        properties.setRatePerSec(2500.5);

        assertThatCode(() -> inputFor(properties))
            .doesNotThrowAnyException();
    }

    @Test
    void rejectsNegativeDirectRuntimeRateBeforeSchedulerStart() {
        CsvDataSetInputProperties properties = baseProperties();
        properties.setRatePerSec(-0.1);

        assertThatThrownBy(() -> inputFor(properties))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("inputs.csv.ratePerSec")
            .hasMessageContaining(">= 0.0");
    }

    @Test
    void rejectsDirectRuntimeTickIntervalBelowManifestRangeBeforeSchedulerStart() {
        CsvDataSetInputProperties properties = baseProperties();
        properties.setTickIntervalMs(99);

        assertThatThrownBy(() -> inputFor(properties))
            .isInstanceOf(io.pockethive.work.config.WorkConfigurationException.class)
            .hasMessageContaining("inputs.csv.tickIntervalMs")
            .hasMessageContaining(">= 100");
    }

    private static CsvDataSetInputProperties baseProperties() {
        CsvDataSetInputProperties properties = new CsvDataSetInputProperties();
        properties.setFilePath("/app/scenario/users.csv");
        properties.setRatePerSec(1.0);
        properties.setRotate(false);
        properties.setSkipHeader(true);
        properties.setDelimiter(",");
        properties.setCharset("UTF-8");
        properties.setStartupDelaySeconds(0);
        properties.setTickIntervalMs(1000);
        return properties;
    }

    private static CsvDataSetWorkInput inputFor(CsvDataSetInputProperties properties) {
        WorkerRuntime runtime = (workerBeanName, message) -> message;
        return new CsvDataSetWorkInput(
            definition(),
            mock(WorkerControlPlaneRuntime.class),
            runtime,
            new ControlPlaneIdentity("swarm-1", "role", "instance-1"),
            properties,
            LoggerFactory.getLogger("test-csv-input")
        );
    }

    private static WorkerDefinition definition() {
        return new WorkerDefinition(
            "csvWorker",
            Object.class,
            WorkerInputType.CSV_DATASET,
            "test-role",
            WorkIoBindings.none(),
            Void.class,
            WorkInputConfig.class,
            WorkOutputConfig.class,
            WorkerOutputType.NONE,
            "Test CSV dataset worker",
            Set.of()
        );
    }
}
