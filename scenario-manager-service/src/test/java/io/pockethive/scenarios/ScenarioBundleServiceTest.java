package io.pockethive.scenarios;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.zip.ZipInputStream;

import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScenarioBundleServiceTest extends ScenarioComponentTestFixture {
    private ScenarioBundleService bundles;

    @BeforeEach
    void setUpPublication() {
        bundles = bundleService();
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

        Scenario created = bundles.create(zip);

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

        assertThatThrownBy(() -> bundles.replace(" ", zip))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Scenario id must not be null or blank");
    }
    @Test void bothAddressesExportSameBundleWithTheirExistingFilenameConventions() throws Exception {
        writeBundleScenario("scenario-id");
        Path nested = Files.createDirectories(scenariosDir.resolve("team")).resolve("folder-name");
        Files.move(scenariosDir.resolve("scenario-id"), nested);
        Files.writeString(nested.resolve("note.txt"), "content");
        scenarios.reload();
        var byId = bundles.downloadByScenarioId("scenario-id");
        var byKey = bundles.downloadByBundleKey("team/folder-name");
        assertThat(byId.fileName()).isEqualTo("scenario-id-bundle.zip");
        assertThat(byKey.fileName()).isEqualTo("folder-name-bundle.zip");
        assertThat(contents(byId.bytes())).isEqualTo(contents(byKey.bytes()));
        assertThat(contents(byId.bytes())).containsEntry("note.txt", "content");
    }
    @Test void missingScenarioHasNoCustomReason() {
        assertThatThrownBy(() -> bundles.downloadByScenarioId("missing"))
            .isInstanceOf(ScenarioDownloadNotFoundException.class).hasMessage(null);
    }
    @Test void missingDirectoryPreservesDifferentAddressingErrors() throws Exception {
        writeBundleScenario("example"); scenarios.reload();
        Files.delete(scenariosDir.resolve("example/scenario.yaml"));
        Files.delete(scenariosDir.resolve("example"));
        assertThatThrownBy(() -> bundles.downloadByScenarioId("example"))
            .isInstanceOf(ScenarioDownloadNotFoundException.class).hasMessage("Scenario bundle not found");
        assertThatThrownBy(() -> bundles.downloadByBundleKey("example"))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("Bundle 'example' not found");
    }
    @Test void malformedBundleRemainsDownloadableByKey() throws Exception {
        Path bundle = Files.createDirectories(scenariosDir.resolve("broken"));
        Files.writeString(bundle.resolve("scenario.yaml"), "broken: ["); scenarios.reload();
        assertThat(contents(bundles.downloadByBundleKey("broken").bytes())).containsEntry("scenario.yaml", "broken: [");
    }
    @Test
    void createNeverClearsOccupiedDirectoryWithAnotherScenarioId() throws Exception {
        writeBundleScenario("other-id");
        Path target = Files.createDirectories(scenariosDir.resolve("bundles")).resolve("new-id");
        Files.move(scenariosDir.resolve("other-id"), target);
        String descriptor = Files.readString(target.resolve("scenario.yaml"));
        Files.writeString(target.resolve("sentinel.txt"), "keep");
        scenarios.reload();
        byte[] upload = scenarioBundleZip(descriptor.replace("other-id", "new-id"));
        assertThatThrownBy(() -> bundles.create(upload))
            .isInstanceOf(java.nio.file.FileAlreadyExistsException.class);
        assertThat(target.resolve("scenario.yaml")).hasContent(descriptor);
        assertThat(target.resolve("sentinel.txt")).hasContent("keep");
        assertThat(scenarios.find("other-id")).isPresent();
    }

    @Test
    void createRejectsEvenAnEmptyOccupiedDirectory() throws Exception {
        writeBundleScenario("example");
        String descriptor = Files.readString(scenariosDir.resolve("example/scenario.yaml"));
        Path target = Files.createDirectories(scenariosDir.resolve("bundles/example"));
        assertThatThrownBy(() -> bundles.create(scenarioBundleZip(descriptor)))
            .isInstanceOf(java.nio.file.FileAlreadyExistsException.class);
        try (var files = Files.list(target)) {
            assertThat(files).isEmpty();
        }
    }

    @Test
    void replaceStillReplacesExistingContents() throws Exception {
        writeBundleScenario("example");
        Path target = scenariosDir.resolve("example");
        String descriptor = Files.readString(target.resolve("scenario.yaml"));
        Files.writeString(target.resolve("old.txt"), "old");
        scenarios.reload();
        bundles.replace("example", scenarioBundleZip(Map.of("scenario.yaml", descriptor, "new.txt", "new")));
        assertThat(target.resolve("old.txt")).doesNotExist();
        assertThat(target.resolve("new.txt")).hasContent("new");
    }

    private Map<String, String> contents(byte[] bytes) throws Exception {
        Map<String, String> result = new HashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                result.put(entry.getName(), new String(zip.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            }
        }
        return result;
    }
}
