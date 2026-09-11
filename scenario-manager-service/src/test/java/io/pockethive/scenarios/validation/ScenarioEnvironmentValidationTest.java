package io.pockethive.scenarios.validation;

import io.pockethive.capabilities.CapabilityCatalogueService;
import io.pockethive.scenarios.config.ScenarioWorkConfigurationComposition;
import io.pockethive.work.config.composition.CurrentWorkConfigurationProviders;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.assertj.core.api.Assertions.assertThat;

class ScenarioEnvironmentValidationTest {
    @TempDir Path bundle;

    @ParameterizedTest
    @CsvSource({"SPRING_RABBITMQ_HOST,true", "SPRING_RABBITMQ_VIRTUALHOST,true",
        "SPRING_RABBITMQ_VIRTUAL_HOST,true", "spring.rabbitmq.virtual-host,true",
        "SPRING_RABBITMQ_ADDRESSES,true", "JAVA_TOOL_OPTIONS,false", "WIZARD_PROOF_CLIENT_SECRET,false"})
    void scenarioValidationUsesCanonicalOverridePolicy(String variable, boolean rejected) throws Exception {
        var catalogue = new CapabilityCatalogueService(Path.of("capabilities"),
            ScenarioWorkConfigurationComposition.createMutationPolicyRegistry());
        catalogue.reload();
        var validator = new ScenarioBundleValidator(catalogue, "latest", "test",
            new CurrentWorkConfigurationProviders().workConfigurationParser());
        Files.writeString(bundle.resolve("scenario.yaml"), """
            protocolVersion: "2.0.0"
            id: env-policy
            name: ENV policy
            template:
              image: swarm-controller:latest
              bees:
                - role: generator
                  image: generator:latest
                  env:
                    %s: "example-value"
                  work: {}
                  config:
                    message:
                      bodyType: SIMPLE
                      body: data
                    inputs:
                      type: SCHEDULER
                      scheduler:
                        ratePerSec: 1
                        maxMessages: 0
                    outputs:
                      type: NONE
            """.formatted(variable));
        var result = validator.validate(new BundleValidationInput(BundleValidationSource.SCENARIO_MANAGER,
            bundle, "env-policy", "env-policy", null, List.of(), null));
        if (rejected) {
            assertThat(result.findings()).singleElement().satisfies(finding -> {
                assertThat(finding.severity()).isEqualTo(ValidationSeverity.ERROR);
                assertThat(finding.path()).startsWith("scenario.yaml:template.bees[0].env.spring.rabbitmq.");
                assertThat(finding.message()).contains("per-worker overrides are unsupported").doesNotContain("example-value");
            });
        } else {
            assertThat(result.findings()).isEmpty();
        }
    }
}
