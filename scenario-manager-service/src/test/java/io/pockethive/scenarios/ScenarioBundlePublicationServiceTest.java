package io.pockethive.scenarios;

import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScenarioBundlePublicationServiceTest extends ScenarioComponentTestFixture {
    private ScenarioBundlePublicationService publication;

    @BeforeEach
    void setUpPublication() {
        ScenarioBundleOrganizationService organization = new ScenarioBundleOrganizationService(scenarios);
        publication = new ScenarioBundlePublicationService(scenarios, organization, validator);
    }

    @Test
    void publishesTheValidatedDescriptorRootFromNestedZip() throws Exception {
        byte[] zip = scenarioBundleZip(Map.of(
            "top-level/scenario.yaml", """
                protocolVersion: "2.0.0"
                id: uploaded-demo
                name: Uploaded Demo
                template:
                  image: ctrl-image:latest
                  bees: []
                """,
            "top-level/note.txt", "copied"));

        Scenario created = publication.create(zip);

        assertThat(created.getId()).isEqualTo("uploaded-demo");
        assertThat(scenariosDir.resolve("bundles/uploaded-demo/scenario.yaml")).exists();
        assertThat(scenariosDir.resolve("bundles/uploaded-demo/note.txt")).hasContent("copied");
        assertThat(scenariosDir.resolve("bundles/uploaded-demo/top-level")).doesNotExist();
    }

    @Test
    void replaceRequiresAnExplicitExpectedScenarioId() throws Exception {
        byte[] zip = scenarioBundleZip("""
            protocolVersion: "2.0.0"
            id: uploaded-demo
            name: Uploaded Demo
            template:
              image: ctrl-image:latest
              bees: []
            """);

        assertThatThrownBy(() -> publication.replace(" ", zip))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Scenario id must not be null or blank");
    }
}
