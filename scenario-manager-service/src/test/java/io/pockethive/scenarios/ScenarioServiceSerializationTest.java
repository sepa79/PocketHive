package io.pockethive.scenarios;

import io.pockethive.scenarios.assets.DatasetAsset;
import io.pockethive.scenarios.assets.ScenarioAssets;
import io.pockethive.scenarios.assets.SutAsset;
import io.pockethive.scenarios.assets.SwarmTemplateAsset;
import io.pockethive.swarm.model.SwarmTemplate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ScenarioServiceSerializationTest {

    @TempDir
    Path tempDir;

    @Test
    void writesScenarioWithAssetsToYaml() throws IOException {
        ScenarioService service = new ScenarioService(tempDir.toString());
        Scenario scenario = new Scenario(
                "scenario-1",
                "Scenario 1",
                "Demo",
                new SwarmTemplate("controller", List.of()),
                new ScenarioAssets(
                        List.of(new SutAsset("sut-1", "SUT", null, "/run.sh", "1.0")),
                        List.of(new DatasetAsset("dataset-1", "Dataset", null, "s3://bucket/data.json", "json")),
                        List.of(new SwarmTemplateAsset("template-1", "Template", null, "sut-1", "dataset-1", 1))
                ),
                List.of()
        );

        service.create(scenario, ScenarioService.Format.YAML);

        Path stored = tempDir.resolve("scenario-1.yaml");
        assertThat(Files.exists(stored)).isTrue();
        assertThat(Files.readString(stored)).contains("assets:");
    }

    @Test
    void readsScenarioWithAssetsFromYaml() throws IOException {
        Files.writeString(tempDir.resolve("sample.yaml"), """
            id: sample
            name: Sample
            assets:
              suts:
                - id: sut-1
                  name: Sample SUT
                  entrypoint: /run.sh
                  version: "1.0"
              datasets:
                - id: dataset-1
                  name: Dataset
                  uri: s3://bucket/data.json
                  format: json
              swarmTemplates:
                - id: template-1
                  name: Template
                  sutId: sut-1
                  datasetId: dataset-1
                  swarmSize: 1
            template:
              image: controller
              bees: []
            tracks: []
            """);

        ScenarioService service = new ScenarioService(tempDir.toString());
        service.init();

        Scenario loaded = service.find("sample").orElseThrow();

        assertThat(loaded.getAssets().getSuts()).hasSize(1);
        assertThat(loaded.getAssets().getSwarmTemplates().getFirst().getSutId()).isEqualTo("sut-1");
    }
}
