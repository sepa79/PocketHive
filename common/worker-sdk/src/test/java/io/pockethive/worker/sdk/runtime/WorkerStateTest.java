package io.pockethive.worker.sdk.runtime;

import io.pockethive.worker.sdk.config.WorkInputConfig;
import io.pockethive.worker.sdk.config.WorkOutputConfig;
import io.pockethive.worker.sdk.config.WorkerCapability;
import io.pockethive.worker.sdk.config.WorkerInputType;
import io.pockethive.worker.sdk.config.WorkerOutputType;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WorkerStateTest {

    @Test
    void seedConfigInitialisesStateOnce() {
        WorkerDefinition definition = new WorkerDefinition(
            "testWorker",
            Object.class,
            WorkerInputType.SCHEDULER,
            "test-role",
            WorkIoBindings.none(),
            TestConfig.class,
            WorkInputConfig.class,
            WorkOutputConfig.class,
            WorkerOutputType.NONE,
            "Test worker",
            Set.of(WorkerCapability.SCHEDULER)
        );
        WorkerState state = new WorkerState(definition);
        TestConfig defaults = new TestConfig(true, 5.0);

        boolean seeded = state.seedConfig(defaults, true);

        assertThat(seeded).isTrue();
        assertThat(state.config(TestConfig.class)).contains(defaults);
        assertThat(state.enabled()).contains(true);
        assertThat(state.seedConfig(new TestConfig(false, 1.0), false)).isFalse();
    }

    @Test
    void updateConfigWithNullConfigClearsState() {
        WorkerDefinition definition = new WorkerDefinition(
            "testWorker",
            Object.class,
            WorkerInputType.SCHEDULER,
            "test-role",
            WorkIoBindings.none(),
            TestConfig.class,
            WorkInputConfig.class,
            WorkOutputConfig.class,
            WorkerOutputType.NONE,
            "Test worker",
            Set.of(WorkerCapability.SCHEDULER)
        );
        WorkerState state = new WorkerState(definition);
        state.seedConfig(new TestConfig(true, 5.0), true);

        state.updateConfig(null, true, null);
        assertThat(state.config(TestConfig.class)).isEmpty();
    }

    @Test
    void updateConfigWithNullConfigPreservesExistingConfig() {
        WorkerDefinition definition = new WorkerDefinition(
            "testWorker",
            Object.class,
            WorkerInputType.SCHEDULER,
            "test-role",
            WorkIoBindings.none(),
            TestConfig.class,
            WorkInputConfig.class,
            WorkOutputConfig.class,
            WorkerOutputType.NONE,
            "Test worker",
            Set.of(WorkerCapability.SCHEDULER)
        );
        WorkerState state = new WorkerState(definition);
        TestConfig defaults = new TestConfig(true, 5.0);
        state.seedConfig(defaults, true);

        state.updateConfig(null, false, Boolean.FALSE);

        assertThat(state.config(TestConfig.class)).contains(defaults);
        assertThat(state.enabled()).contains(false);
    }

    @Test
    void tracksInitialAndDynamicRoutes() {
        WorkerDefinition definition = new WorkerDefinition(
            "testWorker",
            Object.class,
            WorkerInputType.RABBITMQ,
            "test-role",
            WorkIoBindings.of(" in.runtime ", "out.runtime", null),
            TestConfig.class,
            WorkInputConfig.class,
            WorkOutputConfig.class,
            WorkerOutputType.RABBITMQ,
            "Test worker",
            Set.of(WorkerCapability.MESSAGE_DRIVEN)
        );
        WorkerState state = new WorkerState(definition);

        assertThat(state.inboundRoutes()).containsExactly("in.runtime");
        assertThat(state.outboundRoutes()).containsExactly("out.runtime");

        state.addInboundRoute("additional-in");
        state.addOutboundRoute(" additional-out ");

        assertThat(state.inboundRoutes()).containsExactlyInAnyOrder("in.runtime", "additional-in");
        assertThat(state.outboundRoutes()).containsExactlyInAnyOrder("out.runtime", "additional-out");
    }

    private record TestConfig(boolean enabled, double ratePerSec) {
    }
}
