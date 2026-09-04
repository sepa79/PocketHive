package io.pockethive.scenarios;

import java.nio.file.Files;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScenarioBundleWorkspaceServiceTest extends ScenarioComponentTestFixture {
    private ScenarioBundleWorkspaceService workspace;

    @BeforeEach
    void setUpWorkspace() {
        workspace = new ScenarioBundleWorkspaceService(scenarios);
    }

    @Test
    void createsWritesRenamesAndDeletesWorkspaceEntries() throws Exception {
        writeBundleScenario("scenario-1");
        scenarios.reload();

        workspace.createFolder("scenario-1", "notes");
        BundleFilePayload created = workspace.createFile("scenario-1", "notes/readme.txt", "first");
        assertThat(created.content()).isEqualTo("first");
        assertThat(workspace.readTree("scenario-1").nodes())
            .extracting(BundleTreeNode::path)
            .contains("notes", "notes/readme.txt");

        assertThatThrownBy(() -> workspace.writeFile(
            "scenario-1", "notes/readme.txt", "stale", "sha256:stale"))
            .isInstanceOf(WorkspaceConflictException.class);

        BundleFileWriteResult written = workspace.writeFile(
            "scenario-1", "notes/readme.txt", "second", created.revision());
        assertThat(written.revision()).isNotEqualTo(created.revision());
        workspace.renameEntry("scenario-1", "notes/readme.txt", "renamed.txt");
        assertThat(workspace.readFile("scenario-1", "notes/renamed.txt").content()).isEqualTo("second");

        workspace.deleteEntry("scenario-1", "notes/renamed.txt");
        workspace.deleteEntry("scenario-1", "notes");
        assertThat(Files.exists(scenariosDir.resolve("scenario-1/notes"))).isFalse();
    }

    @Test
    void rejectsPathsOutsideTheBundle() throws Exception {
        writeBundleScenario("scenario-1");
        scenarios.reload();

        assertThatThrownBy(() -> workspace.createFile("scenario-1", "../outside.txt", "bad"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Invalid bundle path");
    }
}
