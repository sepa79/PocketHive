package io.pockethive.artemis;

import static io.pockethive.artemis.config.ArtemisEnvironmentKeys.*;
import static org.assertj.core.api.Assertions.*;
import io.pockethive.artemis.api.*;
import io.pockethive.artemis.work.ArtemisWorkBootstrapEnvironment;
import io.pockethive.work.config.*;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

class ArtemisConfigurationTest {
    private final WorkConfigurationParser parser = new WorkConfigurationParser(
        List.of(ArtemisConfiguration.inputParser()), List.of(ArtemisConfiguration.outputParser()));
    private final ArtemisConnectionSettings connection = new ArtemisConnectionSettings("vm://1", "user", "password", 2000);

    @Test void authoredTuningAndResolvedEnvironmentProduceTheSameTypedSettings() {
        var topology = new io.pockethive.artemis.work.ArtemisWorkTopologyResolver(
            new io.pockethive.artemis.topology.ArtemisResourceNames("ph")).resolve("swarm", Set.of("jobs"));
        var adapter = new ArtemisWorkBootstrapEnvironment(connection);
        var authored = configuration(Map.of("consumerWindowBytes", " 0 "), Map.of("persistent", "TRUE"));
        assertThat(parser.validate(authored, WorkConfigurationMode.AUTHORING).problems()).isEmpty();
        var destinations = new java.util.LinkedHashMap<>(topology.channel("jobs").inputEnvironment());
        destinations.putAll(topology.channel("jobs").outputEnvironment());
        var projection = adapter.bootstrap(authored, destinations);
        var resolved = parser.validate(projection.configuration(), WorkConfigurationMode.RESOLVED);
        assertThat(resolved.problems()).isEmpty();
        var env = environment(projection.environment());
        assertThat(Binder.get(env).bind(INPUT_PREFIX, Bindable.of(ArtemisInputSettings.class)).get())
            .isEqualTo(resolved.configuration().inputSettings());
        assertThat(Binder.get(env).bind(OUTPUT_PREFIX, Bindable.of(ArtemisOutputSettings.class)).get())
            .isEqualTo(resolved.configuration().outputSettings());
        assertThat(ArtemisConnectionEnvironment.decode(environment(adapter.connectionEnvironment())::getProperty))
            .isEqualTo(connection);
        assertThat(adapter.connectionEnvironment()).containsEntry(WorkPlaneSelection.ENVIRONMENT, "ARTEMIS");
    }

    @Test void requiredFieldsUnknownFieldsAndPhysicalAuthoringDestinationsFail() {
        for (var settings : List.of(Map.of(), Map.of("consumerWindowBytes", -1),
                Map.of("consumerWindowBytes", 2.9), Map.of("consumerWindowBytes", 0, "queue", "foreign"),
                Map.of("consumerWindowBytes", 0, "typo", true))) {
            assertThat(ArtemisConfiguration.inputParser().validate(settings, "inputs.artemis",
                WorkConfigurationMode.AUTHORING).problems()).isNotEmpty();
        }
        for (var settings : List.of(Map.of(), Map.of("persistent", "yes"),
                Map.of("persistent", true, "address", "foreign"))) {
            assertThat(ArtemisConfiguration.outputParser().validate(settings, "outputs.artemis",
                WorkConfigurationMode.AUTHORING).problems()).isNotEmpty();
        }
        assertThatThrownBy(() -> new ArtemisInputSettings("queue", null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ArtemisOutputSettings("address", null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Binder.get(environment(Map.of(INPUT_QUEUE, "queue")))
            .bind(INPUT_PREFIX, Bindable.of(ArtemisInputSettings.class))).hasRootCauseInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Binder.get(environment(Map.of(OUTPUT_ADDRESS, "address")))
            .bind(OUTPUT_PREFIX, Bindable.of(ArtemisOutputSettings.class))).hasRootCauseInstanceOf(IllegalArgumentException.class);
    }

    @Test void expressionsAreDeferredOnlyBeforeResolutionAndProvisionedEnvironmentCannotBeOverridden() {
        var settings = Map.of("consumerWindowBytes", "{{ vars.window }}");
        var input = ArtemisConfiguration.inputParser();
        assertThat(input.validate(settings, "inputs.artemis", WorkConfigurationMode.AUTHORING).deferredPaths())
            .containsExactly("inputs.artemis.consumerWindowBytes");
        assertThat(input.validate(settings, "inputs.artemis", WorkConfigurationMode.RESOLVED).problems()).isNotEmpty();
        var adapter = new ArtemisWorkBootstrapEnvironment(connection);
        assertThat(adapter.overrideProblems(environment(Map.of(INPUT_QUEUE, "foreign"))::getProperty)).isNotEmpty();
        assertThat(adapter.overrideProblems(environment(Map.of())::getProperty)).isEmpty();
    }

    private static Map<String, Object> configuration(Map<?, ?> input, Map<?, ?> output) {
        return Map.of("inputs", Map.of("type", "ARTEMIS", "artemis", input),
            "outputs", Map.of("type", "ARTEMIS", "artemis", output));
    }

    private static StandardEnvironment environment(Map<String, String> values) {
        var environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, Map.copyOf(values)));
        org.springframework.boot.context.properties.source.ConfigurationPropertySources.attach(environment);
        return environment;
    }
}
