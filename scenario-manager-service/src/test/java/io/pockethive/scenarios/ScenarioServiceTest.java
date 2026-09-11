package io.pockethive.scenarios;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.pockethive.capabilities.CapabilityCatalogueService;
import io.pockethive.scenarios.validation.ScenarioBundleValidator;
import io.pockethive.swarm.model.Bee;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ScenarioServiceTest {

    @TempDir
    Path tempDir;

    private Path scenariosDir;
    private Path capabilitiesDir;
    private CapabilityCatalogueService capabilities;
    private ScenarioBundleValidator validator;
    private ScenarioService service;

    @BeforeEach
    void setUp() throws IOException {
        scenariosDir = Files.createDirectories(tempDir.resolve("scenarios"));
        capabilitiesDir = Files.createDirectories(tempDir.resolve("capabilities"));
        capabilities = new CapabilityCatalogueService(capabilitiesDir.toString(), io.pockethive.scenarios.config.ScenarioWorkConfigurationComposition.createMutationPolicyRegistry());
        validator = validator(null);
        service = new ScenarioService(scenariosDir.toString(), tempDir.resolve("runtime"), validator);
    }

    @Test
    void scenariosWithMatchingManifestsAreAvailable() throws IOException {
        writeManifest("ctrl", "ctrl-image");
        writeManifest("worker", "worker-image");
        capabilities.reload();

        writeScenario("available", """
                protocolVersion: "2.0.0"
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
    void flatScenarioDescriptorsAreIgnored() throws IOException {
        writeManifest("ctrl", "ctrl-image");
        capabilities.reload();

        Files.writeString(scenariosDir.resolve("flat.yaml"), """
                protocolVersion: "2.0.0"
                id: flat
                name: Flat Scenario
                template:
                  image: ctrl-image:latest
                  bees: []
                """);
        Files.writeString(scenariosDir.resolve("scenario.yaml"), """
                protocolVersion: "2.0.0"
                id: root
                name: Root Scenario
                template:
                  image: ctrl-image:latest
                  bees: []
                """);

        service.reload();

        assertThat(service.listAllSummaries()).isEmpty();
        assertThat(service.listBundleTemplates()).isEmpty();
    }

    @Test
    void e2eBundlesAreLoadedOnlyWhenShowTestScenariosEnabled() throws IOException {
        writeManifest("ctrl", "ctrl-image");
        capabilities.reload();

        Path bundle = Files.createDirectories(scenariosDir.resolve("e2e").resolve("e2e-scenario"));
        Files.writeString(bundle.resolve("scenario.yaml"), """
                protocolVersion: "2.0.0"
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
                scenariosDir,
                io.pockethive.controlplane.filesystem.RuntimeFilesystemLayout.of(
                    tempDir.resolve("runtime-no-tests").toString(),
                    tempDir.resolve("runtime-no-tests").toString()),
                false,
                validator);
        withoutTestScenarios.reload();
        assertThat(withoutTestScenarios.listAllSummaries())
                .extracting(ScenarioSummary::id)
                .doesNotContain("e2e-scenario");
    }

    @Test
    void missingManifestMarksScenarioDefunctAndLogsWarning() throws IOException {
        writeManifest("ctrl", "ctrl-image");
        capabilities.reload();

        writeScenario("defunct", """
                protocolVersion: "2.0.0"
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

        Logger logger = (Logger) LoggerFactory.getLogger(ScenarioBundleValidator.class);
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

        writeScenario("healthy", """
                protocolVersion: "2.0.0"
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

        writeScenario("broken", """
                protocolVersion: "2.0.0"
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
    void malformedBundleIsReturnedInBundleCatalogAsDefunct() throws IOException {
        writeManifest("ctrl", "ctrl-image");
        capabilities.reload();

        Path brokenBundle = Files.createDirectories(scenariosDir.resolve("broken-bundle"));
        Files.writeString(brokenBundle.resolve("scenario.yaml"), "id: [not valid yaml");

        Path healthyBundle = Files.createDirectories(scenariosDir.resolve("healthy-bundle"));
        Files.writeString(healthyBundle.resolve("scenario.yaml"), """
                protocolVersion: "2.0.0"
                id: healthy-bundle
                name: Healthy Bundle
                template:
                  image: ctrl-image:latest
                  bees: []
                """);

        service.reload();

        assertThat(service.listBundleTemplates())
                .extracting(BundleTemplateSummary::bundlePath)
                .contains("broken-bundle", "healthy-bundle");
        assertThat(service.listBundleTemplates())
                .filteredOn(entry -> "broken-bundle".equals(entry.bundlePath()))
                .singleElement()
                .satisfies(entry -> {
                    assertThat(entry.id()).isNull();
                    assertThat(entry.defunct()).isTrue();
                    assertThat(entry.defunctReason()).contains("Could not read scenario file");
                });
        assertThat(service.listAvailableSummaries())
                .extracting(ScenarioSummary::id)
                .containsExactly("healthy-bundle");
    }

    @Test
    void duplicateScenarioIdsMarkBothBundlesDefunctInBundleCatalog() throws IOException {
        writeManifest("ctrl", "ctrl-image");
        capabilities.reload();

        Path firstBundle = Files.createDirectories(scenariosDir.resolve("folder-a").resolve("dup"));
        Files.writeString(firstBundle.resolve("scenario.yaml"), """
                protocolVersion: "2.0.0"
                id: dup
                name: Dup A
                template:
                  image: ctrl-image:latest
                  bees: []
                """);

        Path secondBundle = Files.createDirectories(scenariosDir.resolve("folder-b").resolve("dup"));
        Files.writeString(secondBundle.resolve("scenario.yaml"), """
                protocolVersion: "2.0.0"
                id: dup
                name: Dup B
                template:
                  image: ctrl-image:latest
                  bees: []
                """);

        service.reload();

        assertThat(service.listBundleTemplates())
                .filteredOn(entry -> "dup".equals(entry.id()))
                .hasSize(2)
                .allSatisfy(entry -> {
                    assertThat(entry.defunct()).isTrue();
                    assertThat(entry.defunctReason()).contains("Duplicate scenario id 'dup'");
                });
        assertThat(service.find("dup")).isEmpty();
        assertThat(service.findAvailable("dup")).isEmpty();
    }

    @Test
    void quarantineIgnoresDuplicateIdsForActiveBundlesButKeepsQuarantinedEntryDefunct() throws IOException {
        writeManifest("ctrl", "ctrl-image");
        capabilities.reload();

        Path activeBundle = Files.createDirectories(scenariosDir.resolve("active-dup"));
        Files.writeString(activeBundle.resolve("scenario.yaml"), """
                protocolVersion: "2.0.0"
                id: dup
                name: Active Dup
                template:
                  image: ctrl-image:latest
                  bees: []
                """);

        Path quarantinedBundle = Files.createDirectories(scenariosDir.resolve("quarantine").resolve("dup-copy"));
        Files.writeString(quarantinedBundle.resolve("scenario.yaml"), """
                protocolVersion: "2.0.0"
                id: dup
                name: Quarantined Dup
                template:
                  image: ctrl-image:latest
                  bees: []
                """);

        service.reload();

        assertThat(service.findAvailable("dup")).isPresent();
        assertThat(service.listBundleTemplates())
                .filteredOn(entry -> "active-dup".equals(entry.bundlePath()))
                .singleElement()
                .satisfies(entry -> {
                    assertThat(entry.defunct()).isFalse();
                    assertThat(entry.defunctReason()).isNull();
                });
        assertThat(service.listBundleTemplates())
                .filteredOn(entry -> "quarantine/dup-copy".equals(entry.bundlePath()))
                .singleElement()
                .satisfies(entry -> {
                    assertThat(entry.defunct()).isTrue();
                    assertThat(entry.defunctReason()).contains("quarantined");
                });
    }

    @Test
    void loadsTrafficPolicyWhenPresent() throws IOException {
        writeManifest("ctrl", "ctrl-image");
        writeManifest("worker", "worker-image");
        capabilities.reload();

        writeScenario("guarded", """
                protocolVersion: "2.0.0"
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
        service = new ScenarioService(scenariosDir.toString(), validator("experimental"));

        writeScenario("defaulted", """
                protocolVersion: "2.0.0"
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
    void defaultImageTagOverridesScenarioImageTagsWhenConfigured() throws IOException {
        writeManifest("ctrl", "ctrl-image", "runtime");
        writeManifest("worker", "worker-image", "runtime");
        capabilities.reload();
        service = new ScenarioService(scenariosDir.toString(), validator("runtime"));

        writeScenario("runtime-tagged", """
                protocolVersion: "2.0.0"
                id: runtime-tagged
                name: Runtime Tagged Scenario
                template:
                  image: registry.example/pockethive/ctrl-image:latest
                  bees:
                    - role: worker
                      image: registry.example/pockethive/worker-image:0.15
                      work:
                        in:
                          in: a
                        out:
                          out: b
                """);

        service.reload();

        Scenario scenario = service.findAvailable("runtime-tagged").orElseThrow();
        assertThat(scenario.getTemplate().image()).isEqualTo("registry.example/pockethive/ctrl-image:runtime");
        assertThat(scenario.getTemplate().bees())
                .extracting(Bee::image)
                .containsExactly("registry.example/pockethive/worker-image:runtime");
    }

    @Test
    void capabilityLookupIgnoresExplicitImageTags() throws IOException {
        writeManifest("ctrl", "ctrl-image", "latest");
        writeManifest("worker", "worker-image", "latest");
        capabilities = new CapabilityCatalogueService(capabilitiesDir, io.pockethive.scenarios.config.ScenarioWorkConfigurationComposition.createMutationPolicyRegistry());
        capabilities.reload();
        service = new ScenarioService(scenariosDir.toString(), validator(null));

        writeScenario("experimental", """
                protocolVersion: "2.0.0"
                id: experimental
                name: Experimental Scenario
                template:
                  image: 192.168.88.54:5000/pockethive/ctrl-image:experimental
                  bees:
                    - role: worker
                      image: 192.168.88.54:5000/pockethive/worker-image:experimental
                      work:
                        in:
                          in: a
                        out:
                          out: b
                """);

        service.reload();

        assertThat(service.findAvailable("experimental")).isPresent();
        Scenario scenario = service.findAvailable("experimental").orElseThrow();
        assertThat(scenario.getTemplate().image()).isEqualTo("192.168.88.54:5000/pockethive/ctrl-image:experimental");
        assertThat(scenario.getTemplate().bees())
                .extracting(Bee::image)
                .containsExactly("192.168.88.54:5000/pockethive/worker-image:experimental");
    }


    private void writeManifest(String prefix, String imageName) throws IOException {
        writeManifest(prefix, imageName, "latest");
    }

    private ScenarioBundleValidator validator(String defaultImageTag) {
        return new ScenarioBundleValidator(capabilities, defaultImageTag, "test", new io.pockethive.work.config.composition.CurrentWorkConfigurationProviders().workConfigurationParser());
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

    private void writeScenario(String bundleName, String content) throws IOException {
        Path bundle = Files.createDirectories(scenariosDir.resolve(bundleName));
        Files.writeString(bundle.resolve(ScenarioBundleLayout.SCENARIO_DESCRIPTOR_FILE), content);
    }

}
