package io.pockethive.scenarios;

import io.pockethive.scenarios.validation.BundleValidationException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScenarioRuntimeMaterializerTest extends ScenarioComponentTestFixture {
    private ScenarioRuntimeMaterializer materializer;

    @BeforeEach
    void setUpMaterializer() {
        materializer = new ScenarioRuntimeMaterializer(scenarios, validator);
    }

    @Test
    void validatesCurrentBundleBeforeReplacingRuntimeContents() throws Exception {
        writeBundleScenario("scenario-1");
        scenarios.reload();
        Path runtime = scenarios.runtimeDir("sw1");
        Files.createDirectories(runtime);
        Path sentinel = runtime.resolve("sentinel.txt");
        Files.writeString(sentinel, "keep");
        Path sut = Files.createDirectories(scenariosDir.resolve("scenario-1/sut/wrong-id"));
        Files.writeString(sut.resolve("sut.yaml"), "id: different-id\nname: Wrong ID\nendpoints: {}\n");

        assertThatThrownBy(() -> materializer.materialize("scenario-1", "sw1"))
            .isInstanceOf(BundleValidationException.class);
        assertThat(sentinel).hasContent("keep");
    }

    @Test
    void copiesValidBundleIntoAnEmptyRuntimeDirectory() throws Exception {
        writeBundleScenario("scenario-1");
        Files.writeString(scenariosDir.resolve("scenario-1/note.txt"), "copied");
        scenarios.reload();

        Path runtime = materializer.materialize("scenario-1", "sw1");

        assertThat(runtime.resolve("scenario.yaml")).exists();
        assertThat(runtime.resolve("note.txt")).hasContent("copied");
    }
}
