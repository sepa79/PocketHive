package io.pockethive.worker.sdk.runtime;

import io.pockethive.worker.sdk.config.WorkerType;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

import io.pockethive.worker.sdk.capabilities.WorkerCapabilitiesManifest;

class WorkerStateTest {

    @Test
    void seedConfigInitialisesStateOnce() {
        WorkerDefinition definition = new WorkerDefinition(
            "testWorker",
            Object.class,
            WorkerType.GENERATOR,
            "test-role",
            null,
            null,
            null,
            TestConfig.class
        );
        WorkerState state = new WorkerState(definition);
        TestConfig defaults = new TestConfig(true, 5.0);
        Map<String, Object> rawDefaults = Map.of("enabled", true, "ratePerSec", 5.0);

        boolean seeded = state.seedConfig(defaults, rawDefaults, true);

        assertThat(seeded).isTrue();
        assertThat(state.config(TestConfig.class)).contains(defaults);
        assertThat(state.rawConfig()).isEqualTo(rawDefaults);
        assertThat(state.enabled()).contains(true);
        assertThat(state.seedConfig(new TestConfig(false, 1.0), Map.of(), false)).isFalse();
    }

    @Test
    void updateConfigWithEmptyMapClearsRawConfig() {
        WorkerDefinition definition = new WorkerDefinition(
            "testWorker",
            Object.class,
            WorkerType.GENERATOR,
            "test-role",
            null,
            null,
            null,
            TestConfig.class
        );
        WorkerState state = new WorkerState(definition);
        Map<String, Object> rawDefaults = Map.of("enabled", true, "ratePerSec", 5.0);
        state.seedConfig(new TestConfig(true, 5.0), rawDefaults, true);

        state.updateConfig(null, Map.of(), null);
        assertThat(state.rawConfig()).isEmpty();
        assertThat(state.config(TestConfig.class)).isEmpty();
    }

    @Test
    void updateConfigWithNullRawDataPreservesExistingRawConfig() {
        WorkerDefinition definition = new WorkerDefinition(
            "testWorker",
            Object.class,
            WorkerType.GENERATOR,
            "test-role",
            null,
            null,
            null,
            TestConfig.class
        );
        WorkerState state = new WorkerState(definition);
        TestConfig defaults = new TestConfig(true, 5.0);
        Map<String, Object> rawDefaults = Map.of("enabled", true, "ratePerSec", 5.0);
        state.seedConfig(defaults, rawDefaults, true);

        state.updateConfig(null, null, Boolean.FALSE);

        assertThat(state.rawConfig()).isEqualTo(rawDefaults);
        assertThat(state.config(TestConfig.class)).contains(defaults);
        assertThat(state.enabled()).contains(false);
    }

    @Test
    void tracksInitialAndDynamicRoutes() {
        WorkerDefinition definition = new WorkerDefinition(
            "testWorker",
            Object.class,
            WorkerType.MESSAGE,
            "test-role",
            " in.runtime ",
            "out.runtime",
            null,
            TestConfig.class
        );
        WorkerState state = new WorkerState(definition);

        assertThat(state.inboundRoutes()).containsExactly("in.runtime");
        assertThat(state.outboundRoutes()).containsExactly("out.runtime");

        state.addInboundRoute("additional-in");
        state.addOutboundRoute(" additional-out ");

        assertThat(state.inboundRoutes()).containsExactlyInAnyOrder("in.runtime", "additional-in");
        assertThat(state.outboundRoutes()).containsExactlyInAnyOrder("out.runtime", "additional-out");
    }

    @Test
    void capabilitiesManifestPublicationStateResetsOnVersionChange() {
        WorkerDefinition definition = new WorkerDefinition(
            "testWorker",
            Object.class,
            WorkerType.MESSAGE,
            "test-role",
            null,
            null,
            null,
            TestConfig.class
        );
        WorkerState state = new WorkerState(definition);
        WorkerCapabilitiesManifest manifest = new WorkerCapabilitiesManifest(
            "1.0.0",
            "1.0.0",
            "test-role",
            Map.of(
                "schemaVersion", "1.0.0",
                "capabilitiesVersion", "1.0.0",
                "role", "test-role"
            )
        );

        state.setCapabilitiesManifest(manifest);
        assertThat(state.shouldPublishCapabilitiesManifest()).isTrue();

        state.markCapabilitiesManifestPublished();
        assertThat(state.shouldPublishCapabilitiesManifest()).isFalse();

        WorkerCapabilitiesManifest unchanged = new WorkerCapabilitiesManifest(
            "1.0.0",
            "1.0.0",
            "test-role",
            Map.of(
                "schemaVersion", "1.0.0",
                "capabilitiesVersion", "1.0.0",
                "role", "test-role"
            )
        );
        state.setCapabilitiesManifest(unchanged);
        assertThat(state.shouldPublishCapabilitiesManifest()).isFalse();

        WorkerCapabilitiesManifest bumped = new WorkerCapabilitiesManifest(
            "1.0.0",
            "1.1.0",
            "test-role",
            Map.of(
                "schemaVersion", "1.0.0",
                "capabilitiesVersion", "1.1.0",
                "role", "test-role"
            )
        );
        state.setCapabilitiesManifest(bumped);
        assertThat(state.shouldPublishCapabilitiesManifest()).isTrue();
    }

    private record TestConfig(boolean enabled, double ratePerSec) {
    }
}
