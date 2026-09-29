package io.pockethive.scenarios;

import io.pockethive.scenarios.validation.BundleValidationException;
import java.nio.file.Files;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScenarioBundleValidationConsistencyTest extends ScenarioComponentTestFixture {
    @Test
    void existingValidationReadsCurrentProtocolWithoutReload() throws Exception {
        writeBundleScenario("demo");
        scenarios.reload();
        var bundles = bundleService();
        var before = bundles.validateExisting("demo");
        var descriptor = scenariosDir.resolve("demo/scenario.yaml");
        Files.writeString(descriptor, Files.readString(descriptor).replace("2.0.0", "99.0.0"));
        var after = bundles.validateExisting("demo");
        assertThat(after.ok()).isFalse();
        assertThat(after.validation().scenarioProtocolVersion()).isEqualTo("99.0.0");
        assertThat(after.validation()).isNotEqualTo(before.validation());
        assertThatThrownBy(() -> new ScenarioRuntimeMaterializer(scenarios, validator).materialize("demo", "sw1"))
            .isInstanceOf(BundleValidationException.class);
    }

    @Test
    void existingValidationCanRecoverFromCachedParseFailure() throws Exception {
        var bundle = Files.createDirectories(scenariosDir.resolve("demo"));
        Files.writeString(bundle.resolve("scenario.yaml"), "broken: [");
        scenarios.reload();
        assertThat(bundleService().validateExisting("demo").ok()).isFalse();
        writeBundleScenario("demo");
        assertThat(bundleService().validateExisting("demo").ok()).isTrue();
    }

    @Test
    void nestedDescriptorIsRejectedByExistingValidationAndCatalogue() throws Exception {
        writeBundleScenario("demo");
        scenarios.reload();
        var nested = Files.createDirectories(scenariosDir.resolve("demo/nested"));
        Files.writeString(nested.resolve("scenario.yaml"),
            Files.readString(scenariosDir.resolve("demo/scenario.yaml")).replace("demo", "nested"));
        assertThat(bundleService().validateExisting("demo").ok()).isFalse();
        scenarios.reload();
        assertThat(scenarios.listBundleTemplates()).filteredOn(s -> s.bundleKey().equals("demo"))
            .singleElement().satisfies(s -> {
                assertThat(s.defunct()).isTrue();
                assertThat(s.defunctReason()).contains("multiple");
            });
    }

    @Test
    void catalogueUsesFullValidationAndRecoversOnReload() throws Exception {
        writeBundleScenario("demo");
        var descriptor = scenariosDir.resolve("demo/scenario.yaml");
        String valid = Files.readString(descriptor);
        Files.writeString(descriptor, valid.replace("2.0.0", "99.0.0"));
        scenarios.reload();
        assertThat(scenarios.listBundleTemplates()).singleElement()
            .satisfies(s -> assertThat(s.defunct()).isTrue());
        assertThat(scenarios.findAvailable("demo")).isEmpty();
        assertThat(bundleService().validateExisting("demo").ok()).isFalse();
        Files.writeString(descriptor, valid);
        scenarios.reload();
        assertThat(scenarios.findAvailable("demo")).isPresent();
        assertThat(bundleService().validateExisting("demo").ok()).isTrue();
    }

    @Test
    void catalogueIncludesErrorsInBundleFilesNotJustDescriptor() throws Exception {
        writeBundleScenario("demo");
        var sut = Files.createDirectories(scenariosDir.resolve("demo/sut/expected"));
        Files.writeString(sut.resolve("sut.yaml"), "id: different\nname: Different\nendpoints: {}\n");
        scenarios.reload();
        assertThat(scenarios.findAvailable("demo")).isEmpty();
        assertThat(scenarios.listBundleTemplates()).singleElement()
            .satisfies(s -> assertThat(s.defunct()).isTrue());
        assertThat(bundleService().validateExisting("demo").ok()).isFalse();
    }
    @Test
    void authoringWarningsDoNotMakeCatalogueDefunct() throws Exception {
        var bundle = Files.createDirectories(scenariosDir.resolve("demo"));
        Files.writeString(bundle.resolve("scenario.yaml"), """
            protocolVersion: "2.0.0"
            id: demo
            name: Demo
            template:
              image: ctrl-image:latest
              bees:
                - role: worker
                  image: worker-image:latest
                  work: {}
                  config:
                    inputs:
                      type: SCHEDULER
                      scheduler:
                        ratePerSec: "{{ 3 }}"
                        maxMessages: 0
                    outputs:
                      type: NONE
            """);
        scenarios.reload();
        var result = bundleService().validateExisting("demo");
        assertThat(result.ok()).as("findings: %s", result.findings()).isTrue();
        assertThat(result.summary().warnings()).isPositive();
        assertThat(scenarios.findAvailable("demo")).isPresent();
    }

    @Test
    void missingWorkConfigurationIsNotAdvertisedAsRunnable() throws Exception {
        writeBundleScenario("demo");
        var descriptor = scenariosDir.resolve("demo/scenario.yaml");
        Files.writeString(descriptor, Files.readString(descriptor).replace("bees: []", """
            bees:
                - role: worker
                  image: worker-image:latest
                  work: {}
            """.stripTrailing()));
        scenarios.reload();
        var result = bundleService().validateExisting("demo");
        assertThat(result.ok()).isFalse();
        assertThat(result.findings()).anySatisfy(f -> assertThat(f.path()).contains("inputs"));
        assertThat(scenarios.findAvailable("demo")).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"templates/request.yaml", "variables.yaml", "schemas/payload.json"})
    void malformedUtf8IsAValidationErrorAndDoesNotAbortReloadOrStartup(String relativePath) throws Exception {
        writeBundleScenario("healthy");
        writeBundleScenario("broken");
        scenarios.reload();
        var file = scenariosDir.resolve("broken").resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.write(file, new byte[] {(byte) 0xc3, (byte) 0x28});

        var result = bundleService().validateExisting("broken");
        assertThat(result.ok()).isFalse();
        assertThat(result.findings()).anySatisfy(finding -> {
            assertThat(finding.severity()).isEqualTo(io.pockethive.scenarios.validation.ValidationSeverity.ERROR);
            assertThat(finding.message()).contains(relativePath, "UTF-8");
        });
        scenarios.reload();
        assertThat(scenarios.findAvailable("healthy")).isPresent();
        assertThat(scenarios.findAvailable("broken")).isEmpty();
        assertThat(scenarios.listBundleTemplates()).filteredOn(s -> "broken".equals(s.id()))
            .singleElement().satisfies(s -> assertThat(s.defunct()).isTrue());

        var restarted = new ScenarioService(scenariosDir.toString(), tempDir.resolve("runtime"), validator);
        restarted.init();
        assertThat(restarted.findAvailable("healthy")).isPresent();
        assertThat(restarted.findAvailable("broken")).isEmpty();
        var runtime = Files.createDirectories(scenarios.runtimeDir("sw1"));
        Files.writeString(runtime.resolve("sentinel.txt"), "keep");
        assertThatThrownBy(() -> new ScenarioRuntimeMaterializer(scenarios, validator).materialize("broken", "sw1"))
            .isInstanceOf(BundleValidationException.class);
        assertThat(runtime.resolve("sentinel.txt")).hasContent("keep");
    }
}
