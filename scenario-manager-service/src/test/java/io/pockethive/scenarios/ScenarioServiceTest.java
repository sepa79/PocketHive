package io.pockethive.scenarios;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.pockethive.capabilities.CapabilityCatalogueService;
import io.pockethive.scenarios.validation.BundleValidationException;
import io.pockethive.scenarios.validation.ScenarioBundleValidator;
import io.pockethive.swarm.model.Bee;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
        service = new ScenarioService(scenariosDir.toString(), tempDir.resolve("runtime"), capabilities);
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
                scenariosDir.toString(),
                false,
                "",
                "test",
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
                .extracting(ScenarioService.BundleTemplateSummary::bundlePath)
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
        service = new ScenarioService(scenariosDir.toString(), "experimental", capabilities);

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
        service = new ScenarioService(scenariosDir.toString(), "runtime", capabilities);

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
        capabilities = new CapabilityCatalogueService(capabilitiesDir);
        capabilities.reload();
        service = new ScenarioService(scenariosDir.toString(), capabilities);

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

    @Test
    void createBundleFromZipStoresNewBundlesUnderBundlesFolder() throws IOException {
        writeManifest("ctrl", "ctrl-image");
        writeManifest("worker", "worker-image");
        capabilities.reload();

        byte[] zipBytes = scenarioBundleZip("""
                protocolVersion: "2.0.0"
                id: uploaded-demo
                name: Uploaded Demo
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

        Scenario created = service.createBundleFromZip(zipBytes);

        assertThat(created.getId()).isEqualTo("uploaded-demo");
        assertThat(service.bundleDirFor("uploaded-demo"))
                .isEqualTo(scenariosDir.resolve("bundles").resolve("uploaded-demo").toAbsolutePath().normalize());
        assertThat(service.listAllSummaries())
                .extracting(ScenarioSummary::id, ScenarioSummary::folderPath)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("uploaded-demo", "bundles"));
        Path uploadedDescriptor = scenariosDir.resolve("bundles").resolve("uploaded-demo").resolve("scenario.yaml");
        assertThat(uploadedDescriptor).exists();
        assertThat(Files.readString(uploadedDescriptor)).contains("uploaded-demo");
    }

    @Test
    void createBundleFromZipCopiesValidatedDescriptorRootFromNestedZip() throws IOException {
        writeManifest("ctrl", "ctrl-image");
        writeManifest("worker", "worker-image");
        capabilities.reload();

        byte[] zipBytes = scenarioBundleZip(Map.of(
                "top-level/scenario.yaml", """
                    protocolVersion: "2.0.0"
                    id: nested-upload-demo
                    name: Nested Upload Demo
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
                    """,
                "top-level/note.txt", "copied from validated root"));

        Scenario created = service.createBundleFromZip(zipBytes);

        Path targetDir = scenariosDir.resolve("bundles").resolve("nested-upload-demo");
        assertThat(created.getId()).isEqualTo("nested-upload-demo");
        assertThat(targetDir.resolve("scenario.yaml")).exists();
        assertThat(targetDir.resolve("note.txt")).hasContent("copied from validated root");
        assertThat(targetDir.resolve("top-level")).doesNotExist();
    }

    @Test
    void replaceBundleFromZipRequiresExplicitExpectedScenarioId() throws IOException {
        byte[] zipBytes = scenarioBundleZip("""
                protocolVersion: "2.0.0"
                id: uploaded-demo
                name: Uploaded Demo
                template:
                  image: ctrl-image:latest
                  bees: []
                """);

        assertThatThrownBy(() -> service.replaceBundleFromZip(" ", zipBytes))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Scenario id must not be null or blank");
    }

    @Test
    void variablesValidationEmitsCoverageWarningsForRequiredVariables() throws IOException {
        writeBundleScenario("scenario-1");
        writeBundleSut("scenario-1", "sut-A");
        writeBundleSut("scenario-1", "sut-B");
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
    void variablesValidationRejectsUnknownCanonicalSut() throws IOException {
        writeBundleScenario("scenario-1");
        writeBundleSut("scenario-1", "sut-A");
        service.reload();

        String raw = """
                version: 1
                definitions:
                  - name: customerId
                    scope: sut
                    type: string
                profiles:
                  - id: default
                    name: Default
                values:
                  sut:
                    default:
                      ghost:
                        customerId: "123"
                """;

        ScenarioService.VariablesDocument doc = service.parseVariables(raw);

        assertThatThrownBy(() -> service.validateVariables("scenario-1", doc))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("values.sut[default] references unknown sutId 'ghost'")
                .hasMessageContaining("sut/<sutId>/sut.yaml");
    }

    @Test
    void bundleLocalSutRawCanBeWrittenReadAndDeleted() throws IOException {
        writeBundleScenario("scenario-1");
        service.reload();
        String sutId = "sut-A";
        String raw = """
                protocolVersion: "2.0.0"
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

    @Test
    void bundleLocalSutReadRequiresCanonicalSutYaml() throws IOException {
        writeBundleScenario("scenario-1");
        service.reload();
        Path sutRoot = service.bundleDir("scenario-1").resolve("sut");
        Path ymlSut = Files.createDirectories(sutRoot.resolve("sut-yml"));
        Files.writeString(ymlSut.resolve("sut.yml"), """
                protocolVersion: "2.0.0"
                id: sut-yml
                name: SUT YML
                """);
        Path jsonSut = Files.createDirectories(sutRoot.resolve("sut-json"));
        Files.writeString(jsonSut.resolve("sut.json"), """
                {"id":"sut-json","name":"SUT JSON"}
                """);

        assertThatThrownBy(() -> service.readBundleSut("scenario-1", "sut-yml"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("has no sut.yaml");
        assertThatThrownBy(() -> service.readBundleSut("scenario-1", "sut-json"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("has no sut.yaml");
        assertThatThrownBy(() -> service.listSutIds("scenario-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Failed to parse bundle-local SUT 'sut-json'")
                .hasMessageContaining("has no sut.yaml");

        writeBundleScenario("scenario-2");
        sutRoot = service.bundleDir("scenario-2").resolve("sut");
        Path wrongIdSut = Files.createDirectories(sutRoot.resolve("sut-wrong-id"));
        Files.writeString(wrongIdSut.resolve("sut.yaml"), """
                protocolVersion: "2.0.0"
                id: different-id
                name: Wrong ID SUT
                """);
        service.reload();

        assertThatThrownBy(() -> service.readBundleSut("scenario-2", "sut-wrong-id"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not match directory name");
        assertThatThrownBy(() -> service.listSutIds("scenario-2"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Failed to parse bundle-local SUT 'sut-wrong-id'")
                .hasMessageContaining("does not match directory name");
    }

    @Test
    void prepareRuntimeDirectoryRejectsCurrentBrokenBundleBeforeClearingTarget() throws IOException {
        writeManifest("ctrl", "ctrl-image");
        capabilities.reload();
        writeBundleScenario("scenario-1");
        service.reload();

        Path runtimeDir = service.runtimeDir("sw1");
        Files.createDirectories(runtimeDir);
        Path sentinel = runtimeDir.resolve("sentinel.txt");
        Files.writeString(sentinel, "keep");

        Path sutDir = Files.createDirectories(service.bundleDir("scenario-1").resolve("sut").resolve("wrong-id"));
        Files.writeString(sutDir.resolve("sut.yaml"), """
                protocolVersion: "2.0.0"
                id: different-id
                name: Wrong ID
                """);

        assertThatThrownBy(() -> service.prepareRuntimeDirectory("scenario-1", "sw1"))
                .isInstanceOf(BundleValidationException.class);
        assertThat(Files.readString(sentinel)).isEqualTo("keep");
    }

    @Test
    void variablesSupportObjectTypeForSutScope() throws IOException {
        writeBundleScenario("scenario-1");
        writeBundleSut("scenario-1", "webauth-local");
        service.reload();

        String raw = """
                version: 1
                definitions:
                  - name: customers
                    scope: sut
                    type: object
                    required: true
                profiles:
                  - id: default
                    name: Default
                values:
                  sut:
                    default:
                      webauth-local:
                        customers:
                          custA:
                            client: "1211815181"
                            currency: "USD"
                          custB:
                            client: "1211815182"
                            currency: "GBP"
                """;

        service.writeVariables("scenario-1", raw);

        ScenarioService.VariablesResolutionResult resolved =
                service.resolveVariables("scenario-1", "default", "webauth-local");

        assertThat(resolved.vars()).containsKey("customers");
        Object customersRaw = resolved.vars().get("customers");
        assertThat(customersRaw).isInstanceOf(Map.class);
        Map<?, ?> customers = (Map<?, ?>) customersRaw;
        assertThat(customers.containsKey("custA")).isTrue();
        assertThat(customers.containsKey("custB")).isTrue();
        assertThat(customers.get("custA")).isInstanceOf(Map.class);
    }

    @Test
    void variablesRejectNonObjectValueWhenTypeIsObject() throws IOException {
        writeBundleScenario("scenario-1");
        writeBundleSut("scenario-1", "webauth-local");
        service.reload();

        String raw = """
                version: 1
                definitions:
                  - name: customers
                    scope: sut
                    type: object
                    required: true
                profiles:
                  - id: default
                    name: Default
                values:
                  sut:
                    default:
                      webauth-local:
                        customers: "not-an-object"
                """;

        assertThatThrownBy(() -> service.writeVariables("scenario-1", raw))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be an object");
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

    private void writeScenario(String bundleName, String content) throws IOException {
        Path bundle = Files.createDirectories(scenariosDir.resolve(bundleName));
        Files.writeString(bundle.resolve(ScenarioBundleLayout.SCENARIO_DESCRIPTOR_FILE), content);
    }

    private void writeBundleScenario(String scenarioId) throws IOException {
        Path bundle = scenariosDir.resolve(scenarioId);
        Files.createDirectories(bundle);
        Files.writeString(bundle.resolve("scenario.yaml"), """
                protocolVersion: "2.0.0"
                id: %s
                name: %s
                template:
                  image: ctrl-image:latest
                  bees: []
                """.formatted(scenarioId, scenarioId));
    }

    private void writeBundleSut(String scenarioId, String sutId) throws IOException {
        Path sutDir = Files.createDirectories(service.bundleDir(scenarioId).resolve("sut").resolve(sutId));
        Files.writeString(sutDir.resolve("sut.yaml"), """
                protocolVersion: "2.0.0"
                id: %s
                name: %s
                """.formatted(sutId, sutId));
    }

    private byte[] scenarioBundleZip(String scenarioYaml) throws IOException {
        return scenarioBundleZip(Map.of("scenario.yaml", scenarioYaml));
    }

    private byte[] scenarioBundleZip(Map<String, String> entries) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return out.toByteArray();
    }
}
