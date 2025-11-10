package io.pockethive.worker.sdk.autoconfigure;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import io.pockethive.worker.sdk.config.PocketHiveWorkerProperties;
import io.pockethive.worker.sdk.config.WorkInputConfig;
import io.pockethive.worker.sdk.config.WorkOutputConfig;
import io.pockethive.worker.sdk.config.WorkerCapability;
import io.pockethive.worker.sdk.config.WorkerInputType;
import io.pockethive.worker.sdk.config.WorkerOutputType;
import io.pockethive.worker.sdk.runtime.WorkIoBindings;
import io.pockethive.worker.sdk.runtime.WorkerControlPlaneRuntime;
import io.pockethive.worker.sdk.runtime.WorkerDefinition;
import io.pockethive.worker.sdk.runtime.WorkerRegistry;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(MockitoExtension.class)
class PocketHiveWorkerDefaultsInitializerTest {

    @Mock
    private WorkerControlPlaneRuntime controlPlaneRuntime;

    private WorkerRegistry workerRegistry;
    private WorkerDefinition definition;

    @BeforeEach
    void setUp() {
        definition = new WorkerDefinition(
            "testWorker",
            Object.class,
            WorkerInputType.RABBIT,
            "test-role",
            WorkIoBindings.none(),
            TestWorkerConfig.class,
            WorkInputConfig.class,
            WorkOutputConfig.class,
            WorkerOutputType.NONE,
            "Test worker",
            Set.of(WorkerCapability.MESSAGE_DRIVEN)
        );
        workerRegistry = new WorkerRegistry(List.of(definition));
    }

    @Test
    void registersDefaultsForMatchingRole() {
        TestWorkerProperties props = new TestWorkerProperties();
        props.setConfig(Map.of("value", "demo"));
        org.assertj.core.api.Assertions.assertThat(props.rawConfig()).containsEntry("value", "demo");
        PocketHiveWorkerDefaultsInitializer initializer = new PocketHiveWorkerDefaultsInitializer(
            workerRegistry,
            controlPlaneRuntime,
            List.of(props)
        );

        initializer.afterSingletonsInstantiated();

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(controlPlaneRuntime).registerDefaultConfig(eq("testWorker"), captor.capture());
        org.assertj.core.api.Assertions.assertThat(captor.getValue())
            .containsEntry("value", "demo");
    }

    @Test
    void skipsRegistrationWhenConfigEmpty() {
        TestWorkerProperties props = new TestWorkerProperties();
        PocketHiveWorkerDefaultsInitializer initializer = new PocketHiveWorkerDefaultsInitializer(
            workerRegistry,
            controlPlaneRuntime,
            List.of(props)
        );

        initializer.afterSingletonsInstantiated();

        verify(controlPlaneRuntime, never()).registerDefaultConfig(eq("testWorker"), any());
    }

    @Test
    void throwsWhenRoleMissing() {
        assertThatThrownBy(() -> new TestWorkerProperties("", TestWorkerConfig.class))
            .isInstanceOf(IllegalArgumentException.class);
    }

    private static final class TestWorkerProperties extends PocketHiveWorkerProperties<TestWorkerConfig> {

        TestWorkerProperties() {
            this("test-role", TestWorkerConfig.class);
        }

        TestWorkerProperties(String role, Class<TestWorkerConfig> type) {
            super(role, type);
        }
    }

    private record TestWorkerConfig(String value) {
    }
}
