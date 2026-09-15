package io.pockethive.work.config.composition;

import static org.assertj.core.api.Assertions.*;
import io.pockethive.work.config.WorkConfigurationMode;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CurrentWorkConfigurationProvidersTest {
    @Test void neutralAuthoringAcceptsArtemisTuningAndRejectsItsPhysicalDestinations() {
        var parser = new CurrentWorkConfigurationProviders().workConfigurationParser();
        assertThat(parser.validate(configuration(Map.of("consumerWindowBytes", 0)),
            WorkConfigurationMode.AUTHORING).problems()).isEmpty();
        assertThat(parser.validate(configuration(Map.of("consumerWindowBytes", 0, "queue", "foreign")),
            WorkConfigurationMode.AUTHORING).problems()).isNotEmpty();
    }

    @Test void deploymentSelectionRequiresAnExplicitWorkPlaneAndRejectsInputOnlyAdapters() {
        assertThat(CurrentWorkPlaneSelection.resolve(key -> "ARTEMIS").environment())
            .containsEntry("POCKETHIVE_WORK_TYPE", "ARTEMIS");
        assertThatThrownBy(() -> CurrentWorkPlaneSelection.resolve(key -> null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CurrentWorkPlaneSelection.resolve(key -> "CSV_DATASET")).isInstanceOf(IllegalArgumentException.class);
    }

    private static Map<String, Object> configuration(Map<String, Object> input) {
        return Map.of("inputs", Map.of("type", "ARTEMIS", "artemis", input),
            "outputs", Map.of("type", "ARTEMIS", "artemis", Map.of("persistent", true)));
    }
}
