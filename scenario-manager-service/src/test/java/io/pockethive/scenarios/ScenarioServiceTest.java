package io.pockethive.scenarios;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.pockethive.capabilities.CapabilityCatalogueService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ScenarioServiceTest {

    @TempDir
    Path tempDir;

    private Path scenariosDir;
    private Path capabilitiesDir;
    private CapabilityCatalogueService capabilities;
    private ScenarioService service;

    @BeforeEach
    void setUp() throws IOException {
        scenariosDir = Files.createDirectories(tempDir.resolve("scenarios"));
        capabilitiesDir = Files.createDirectories(tempDir.resolve("capabilities"));
        capabilities = new CapabilityCatalogueService(capabilitiesDir.toString());
        service = new ScenarioService(scenariosDir.toString(), capabilities);
    }

    @Test
    void scenariosWithMatchingManifestsAreAvailable() throws IOException {
        writeManifest("ctrl", "ctrl-image");
        writeManifest("worker", "worker-image");
        capabilities.reload();

        writeScenario("available.yaml", """
                id: available
                name: Available Scenario
                template:
                  image: ctrl-image:latest
                  bees:
                    - role: worker
                      image: worker-image:latest
                      work:
                        in: a
                        out: b
                """);

        service.reload();

        assertThat(service.listAvailableSummaries())
                .extracting(ScenarioSummary::id)
                .containsExactly("available");
        assertThat(service.listDefunctSummaries()).isEmpty();
        assertThat(service.findAvailable("available")).isPresent();
    }

    @Test
    void missingManifestMarksScenarioDefunctAndLogsWarning() throws IOException {
        writeManifest("ctrl", "ctrl-image");
        capabilities.reload();

        writeScenario("defunct.yaml", """
                id: defunct
                name: Defunct Scenario
                template:
                  image: ctrl-image:latest
                  bees:
                    - role: worker
                      image: worker-image:latest
                      work:
                        in: a
                        out: b
                """);

        Logger logger = (Logger) LoggerFactory.getLogger(ScenarioService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        try {
            service.reload();
        } finally {
            logger.detachAppender(appender);
        }

        assertThat(service.listAvailableSummaries()).isEmpty();
        assertThat(service.listDefunctSummaries())
                .extracting(ScenarioSummary::id)
                .containsExactly("defunct");
        assertThat(appender.list.stream()
                .filter(event -> event.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage))
                .anyMatch(message -> message.contains("worker-image:latest"));
    }

    @Test
    void mixedValidityKeepsOnlyHealthyScenariosAvailable() throws IOException {
        writeManifest("ctrl", "ctrl-image");
        writeManifest("worker", "worker-image");
        capabilities.reload();

        writeScenario("healthy.yaml", """
                id: healthy
                name: Healthy Scenario
                template:
                  image: ctrl-image:latest
                  bees:
                    - role: worker
                      image: worker-image:latest
                      work:
                        in: a
                        out: b
                """);

        writeScenario("broken.yaml", """
                id: broken
                name: Broken Scenario
                template:
                  image: ctrl-image:latest
                  bees:
                    - role: worker
                      image: missing-image:latest
                      work:
                        in: x
                        out: y
                """);

        service.reload();

        assertThat(service.listAvailableSummaries())
                .extracting(ScenarioSummary::id)
                .containsExactly("healthy");
        assertThat(service.listDefunctSummaries())
                .extracting(ScenarioSummary::id)
                .containsExactly("broken");
        assertThat(service.findAvailable("broken")).isEmpty();
        assertThat(service.find("broken")).isPresent();
    }

    private void writeManifest(String prefix, String imageName) throws IOException {
        String manifest = """
                {
                  "schemaVersion": "1.0",
                  "capabilitiesVersion": "1.0",
                  "role": "%s",
                  "image": {
                    "name": "%s",
                    "tag": "latest"
                  }
                }
                """.formatted(prefix, imageName);
        Files.writeString(capabilitiesDir.resolve(prefix + "-manifest.json"), manifest);
    }

    private void writeScenario(String fileName, String content) throws IOException {
        Files.writeString(scenariosDir.resolve(fileName), content);
    }
}
