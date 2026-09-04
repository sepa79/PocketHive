package io.pockethive.scenarios;

import io.pockethive.capabilities.CapabilityCatalogueService;
import io.pockethive.scenarios.validation.ScenarioBundleValidator;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;

abstract class ScenarioComponentTestFixture {
    @TempDir
    Path tempDir;

    protected Path scenariosDir;
    protected Path capabilitiesDir;
    protected CapabilityCatalogueService capabilities;
    protected ScenarioBundleValidator validator;
    protected ScenarioService scenarios;

    @BeforeEach
    final void createScenarioFixture() throws IOException {
        scenariosDir = Files.createDirectories(tempDir.resolve("scenarios"));
        capabilitiesDir = Files.createDirectories(tempDir.resolve("capabilities"));
        capabilities = new CapabilityCatalogueService(capabilitiesDir);
        writeManifest("ctrl", "ctrl-image");
        writeManifest("worker", "worker-image");
        capabilities.reload();
        validator = new ScenarioBundleValidator(capabilities, null, "test");
        scenarios = new ScenarioService(scenariosDir.toString(), tempDir.resolve("runtime"), validator);
    }

    protected void writeBundleScenario(String scenarioId) throws IOException {
        Path bundle = Files.createDirectories(scenariosDir.resolve(scenarioId));
        Files.writeString(bundle.resolve(ScenarioBundleLayout.SCENARIO_DESCRIPTOR_FILE), """
            protocolVersion: "2.0.0"
            id: %s
            name: %s
            template:
              image: ctrl-image:latest
              bees: []
            """.formatted(scenarioId, scenarioId));
    }

    protected void writeBundleSut(String scenarioId, String sutId) throws IOException {
        Path sut = Files.createDirectories(scenariosDir.resolve(scenarioId).resolve("sut").resolve(sutId));
        Files.writeString(sut.resolve(ScenarioBundleLayout.SUT_DESCRIPTOR_FILE), """
            id: %s
            name: %s
            endpoints: {}
            """.formatted(sutId, sutId));
    }

    protected byte[] scenarioBundleZip(String scenarioYaml) throws IOException {
        return scenarioBundleZip(Map.of(ScenarioBundleLayout.SCENARIO_DESCRIPTOR_FILE, scenarioYaml));
    }

    protected byte[] scenarioBundleZip(Map<String, String> entries) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return out.toByteArray();
    }

    private void writeManifest(String role, String imageName) throws IOException {
        Files.writeString(capabilitiesDir.resolve(role + "-manifest.json"), """
            {
              "schemaVersion": "1.0",
              "capabilitiesVersion": "1.0",
              "role": "%s",
              "image": {
                "name": "%s",
                "tag": "latest"
              }
            }
            """.formatted(role, imageName));
    }
}
