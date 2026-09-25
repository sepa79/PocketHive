package io.pockethive.scenarios;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.HashMap;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScenarioBundleDownloadServiceTest extends ScenarioComponentTestFixture {
    private ScenarioBundleDownloadService downloads;
    @BeforeEach void setUpDownloads() { downloads = new ScenarioBundleDownloadService(scenarios, new ScenarioBundleZipExporter()); }
    @Test void bothAddressesExportSameBundleWithTheirExistingFilenameConventions() throws Exception {
        writeBundleScenario("scenario-id");
        Path nested = Files.createDirectories(scenariosDir.resolve("team")).resolve("folder-name");
        Files.move(scenariosDir.resolve("scenario-id"), nested);
        Files.writeString(nested.resolve("note.txt"), "content");
        scenarios.reload();
        var byId = downloads.byScenarioId("scenario-id");
        var byKey = downloads.byBundleKey("team/folder-name");
        assertThat(byId.fileName()).isEqualTo("scenario-id-bundle.zip");
        assertThat(byKey.fileName()).isEqualTo("folder-name-bundle.zip");
        assertThat(contents(byId.bytes())).isEqualTo(contents(byKey.bytes()));
        assertThat(contents(byId.bytes())).containsEntry("note.txt", "content");
    }
    @Test void missingScenarioHasNoCustomReason() {
        assertThatThrownBy(() -> downloads.byScenarioId("missing"))
            .isInstanceOf(ScenarioDownloadNotFoundException.class).hasMessage(null);
    }
    @Test void missingDirectoryPreservesDifferentAddressingErrors() throws Exception {
        writeBundleScenario("example"); scenarios.reload();
        Files.delete(scenariosDir.resolve("example/scenario.yaml"));
        Files.delete(scenariosDir.resolve("example"));
        assertThatThrownBy(() -> downloads.byScenarioId("example"))
            .isInstanceOf(ScenarioDownloadNotFoundException.class).hasMessage("Scenario bundle not found");
        assertThatThrownBy(() -> downloads.byBundleKey("example"))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("Bundle 'example' not found");
    }
    @Test void malformedBundleRemainsDownloadableByKey() throws Exception {
        Path bundle = Files.createDirectories(scenariosDir.resolve("broken"));
        Files.writeString(bundle.resolve("scenario.yaml"), "broken: ["); scenarios.reload();
        assertThat(contents(downloads.byBundleKey("broken").bytes())).containsEntry("scenario.yaml", "broken: [");
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
