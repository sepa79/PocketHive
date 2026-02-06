package io.pockethive.scenarios;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.pockethive.capabilities.CapabilityCatalogueService;
import io.pockethive.swarm.model.Bee;
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
                        in:
                          in: a
                        out:
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
    void e2eBundlesAreLoadedOnlyWhenShowTestScenariosEnabled() throws IOException {
        writeManifest("ctrl", "ctrl-image");
        capabilities.reload();

        Path bundle = Files.createDirectories(scenariosDir.resolve("e2e").resolve("e2e-scenario"));
        Files.writeString(bundle.resolve("scenario.yaml"), """
                id: e2e-scenario
                name: E2E Scenario
                template:
                  image: ctrl-image:latest
                  bees: []
                """);

        service.reload();
        assertThat(service.listAllSummaries())
                .extracting(ScenarioSummary::id)
                .contains("e2e-scenario");

        ScenarioService withoutTestScenarios = new ScenarioService(
                scenariosDir.toString(),
                false,
                "",
                capabilities);
        withoutTestScenarios.reload();
        assertThat(withoutTestScenarios.listAllSummaries())
                .extracting(ScenarioSummary::id)
                .doesNotContain("e2e-scenario");
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
                        in:
                          in: a
                        out:
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
                        in:
                          in: a
                        out:
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
                        in:
                          in: x
                        out:
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

    @Test
    void loadsTrafficPolicyWhenPresent() throws IOException {
        writeManifest("ctrl", "ctrl-image");
        writeManifest("worker", "worker-image");
        capabilities.reload();

        writeScenario("guarded.yaml", """
                id: guarded
                name: Guarded Scenario
                trafficPolicy:
                  bufferGuard:
                    enabled: true
                    queueAlias: gen-out
                    targetDepth: 120
                    minDepth: 80
                    maxDepth: 160
                    samplePeriod: 5s
                    movingAverageWindow: 3
                    adjust:
                      maxIncreasePct: 10
                      maxDecreasePct: 15
                      minRatePerSec: 1
                      maxRatePerSec: 10
                template:
                  image: ctrl-image:latest
                  bees:
                    - role: worker
                      image: worker-image:latest
                      work:
                        in:
                          in: a
                        out:
                          out: b
                """);

        service.reload();

        Scenario scenario = service.find("guarded").orElseThrow();
        assertThat(scenario.getTrafficPolicy()).isNotNull();
        assertThat(scenario.getTrafficPolicy().bufferGuard()).isNotNull();
        assertThat(scenario.getTrafficPolicy().bufferGuard().queueAlias()).isEqualTo("gen-out");
        assertThat(scenario.getTrafficPolicy().bufferGuard().adjust().maxIncreasePct()).isEqualTo(10);
    }

    @Test
    void defaultsImageTagsWhenMissing() throws IOException {
        writeManifest("ctrl", "ctrl-image", "experimental");
        writeManifest("worker", "worker-image", "experimental");
        capabilities.reload();
        service = new ScenarioService(scenariosDir.toString(), "experimental", capabilities);

        writeScenario("defaulted.yaml", """
                id: defaulted
                name: Defaulted Scenario
                template:
                  image: ctrl-image
                  bees:
                    - role: worker
                      image: worker-image
                      work:
                        in:
                          in: a
                        out:
                          out: b
                """);

        service.reload();

        Scenario scenario = service.findAvailable("defaulted").orElseThrow();
        assertThat(scenario.getTemplate().image()).isEqualTo("ctrl-image:experimental");
        assertThat(scenario.getTemplate().bees())
                .extracting(Bee::image)
                .containsExactly("worker-image:experimental");
    }

    @Test
    void variablesValidationEmitsCoverageWarningsForRequiredVariables() throws IOException {
        writeBundleScenario("scenario-1");
        Path bundle = service.bundleDir("scenario-1");
        Files.createDirectories(bundle.resolve("sut").resolve("sut-A"));
        Files.createDirectories(bundle.resolve("sut").resolve("sut-B"));
        service.reload();

        String raw = """
                version: 1
                definitions:
                  - name: loopCount
                    scope: global
                    type: int
                    required: true
                  - name: customerId
                    scope: sut
                    type: string
                    required: true
                profiles:
                  - id: default
                    name: Default
                  - id: france
                    name: France
                values:
                  global:
                    default:
                      loopCount: 3
                    france: {}
                  sut:
                    default:
                      sut-A:
                        customerId: "123"
                      sut-B: {}
                    france:
                      sut-A: {}
                """;

        ScenarioService.VariablesDocument doc = service.parseVariables(raw);
        ScenarioService.VariablesValidationResult result = service.validateVariables("scenario-1", doc);

        assertThat(result.warnings()).contains(
                "profile 'france' is missing required global variables: loopCount",
                "profile 'default' sut 'sut-B' is missing required sut variables: customerId",
                "profile 'france' sut 'sut-A' is missing required sut variables: customerId",
                "profile 'france' sut 'sut-B' is missing required sut variables: customerId"
        );
    }

    @Test
    void bundleLocalSutRawCanBeWrittenReadAndDeleted() throws IOException {
        writeBundleScenario("scenario-1");
        service.reload();
        String sutId = "sut-A";
        String raw = """
                id: sut-A
                name: SUT A
                endpoints:
                  api:
                    id: api
                    kind: http
                    baseUrl: http://example.local
                """;

        service.writeBundleSutRaw("scenario-1", sutId, raw);

        assertThat(service.readBundleSutRaw("scenario-1", sutId)).contains(raw);
        assertThat(service.readBundleSut("scenario-1", sutId).id()).isEqualTo("sut-A");
        assertThat(service.listSutIds("scenario-1")).contains("sut-A");

        service.deleteBundleSut("scenario-1", sutId);

        assertThat(service.readBundleSutRaw("scenario-1", sutId)).isNull();
        assertThat(service.listSutIds("scenario-1")).doesNotContain("sut-A");
    }

    private void writeManifest(String prefix, String imageName) throws IOException {
        writeManifest(prefix, imageName, "latest");
    }

    private void writeManifest(String prefix, String imageName, String tag) throws IOException {
        String manifest = """
                {
                  "schemaVersion": "1.0",
                  "capabilitiesVersion": "1.0",
                  "role": "%s",
                  "image": {
                    "name": "%s",
                    "tag": "%s"
                  }
                }
                """.formatted(prefix, imageName, tag);
        Files.writeString(capabilitiesDir.resolve(prefix + "-manifest.json"), manifest);
    }

    private void writeScenario(String fileName, String content) throws IOException {
        Files.writeString(scenariosDir.resolve(fileName), content);
    }

	    private void writeBundleScenario(String scenarioId) throws IOException {
	        Path bundle = scenariosDir.resolve(scenarioId);
	        Files.createDirectories(bundle);
	        Files.writeString(bundle.resolve("scenario.yaml"), """
	                id: %s
	                name: %s
                template:
                  image: ctrl-image:latest
                  bees: []
                """.formatted(scenarioId, scenarioId));
    }
}
