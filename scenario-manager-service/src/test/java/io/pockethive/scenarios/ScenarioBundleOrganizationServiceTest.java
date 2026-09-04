package io.pockethive.scenarios;

import java.nio.file.Files;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScenarioBundleOrganizationServiceTest extends ScenarioComponentTestFixture {
    private ScenarioBundleOrganizationService organization;

    @BeforeEach
    void setUpOrganization() {
        organization = new ScenarioBundleOrganizationService(scenarios);
    }

    @Test
    void createsListsMovesAndDeletesTopLevelBundleFolders() throws Exception {
        writeBundleScenario("scenario-1");
        scenarios.reload();

        organization.createFolder("group");
        assertThat(organization.listFolders()).contains("group");
        organization.moveScenario("scenario-1", "group");
        assertThat(scenariosDir.resolve("group/scenario-1/scenario.yaml")).exists();

        organization.deleteBundle("group/scenario-1");
        assertThat(scenariosDir.resolve("group/scenario-1")).doesNotExist();
        organization.deleteFolder("group");
        assertThat(scenariosDir.resolve("group")).doesNotExist();
    }

    @Test
    void refusesToDeleteNonEmptyFolder() throws Exception {
        organization.createFolder("group");
        Files.writeString(scenariosDir.resolve("group/note.txt"), "kept");

        assertThatThrownBy(() -> organization.deleteFolder("group"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Folder must be empty");
    }
}
