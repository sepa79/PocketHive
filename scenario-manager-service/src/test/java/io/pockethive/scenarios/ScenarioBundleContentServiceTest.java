package io.pockethive.scenarios;

import java.nio.file.Files;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScenarioBundleContentServiceTest extends ScenarioComponentTestFixture {
    private ScenarioBundleContentService content;

    @BeforeEach
    void setUpContent() {
        content = new ScenarioBundleContentService(scenarios, validator);
    }

    @Test
    void editsScenarioPlanSchemasAndTemplatesWithinTheBundle() throws Exception {
        writeBundleScenario("scenario-1");
        scenarios.reload();

        String updated = Files.readString(scenariosDir.resolve("scenario-1/scenario.yaml"))
            .replace("name: scenario-1", "name: Updated");
        content.writeScenarioRaw("scenario-1", updated);
        assertThat(content.readScenarioRaw("scenario-1")).isEqualTo(updated);

        Scenario scenario = content.writePlan("scenario-1", Map.of("steps", java.util.List.of()));
        assertThat(scenario.getPlan()).containsKey("steps");

        content.writeSchemaFile("scenario-1", "schemas/request.json", "{\"type\":\"object\"}");
        assertThat(content.listSchemaFiles("scenario-1")).containsExactly("schemas/request.json");
        assertThat(content.readFile("scenario-1", "schemas/request.json")).contains("object");

        content.writeTemplate("scenario-1", "templates/http/request.yaml", "method: GET\n");
        content.renameTemplate("scenario-1", "templates/http/request.yaml", "templates/http/renamed.yaml");
        assertThat(content.listTemplateFiles("scenario-1")).containsExactly("templates/http/renamed.yaml");
        content.deleteTemplate("scenario-1", "templates/http/renamed.yaml");
        assertThat(content.listTemplateFiles("scenario-1")).isEmpty();
    }

    @Test
    void rejectsDescriptorWhoseIdDoesNotMatchTheAddressedScenario() throws Exception {
        writeBundleScenario("scenario-1");
        scenarios.reload();

        assertThatThrownBy(() -> content.writeScenarioRaw("scenario-1", """
            protocolVersion: "2.0.0"
            id: another
            name: Another
            template:
              image: ctrl-image:latest
              bees: []
            """))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("does not match path id");
    }
}
