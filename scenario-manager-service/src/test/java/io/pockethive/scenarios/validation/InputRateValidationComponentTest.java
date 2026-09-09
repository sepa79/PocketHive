package io.pockethive.scenarios.validation;

import static org.assertj.core.api.Assertions.assertThat;

import io.pockethive.capabilities.CapabilityCatalogueService;
import io.pockethive.work.config.WorkerInputType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class InputRateValidationComponentTest {
    @TempDir Path bundle;
    private ScenarioBundleValidator validator;

    @BeforeEach
    void loadCatalogue() throws Exception {
        var catalogue = new CapabilityCatalogueService(Path.of("capabilities"));
        catalogue.reload();
        validator = new ScenarioBundleValidator(catalogue, "latest", "test");
    }

    @ParameterizedTest
    @EnumSource(value = WorkerInputType.class, names = "RABBITMQ", mode = EnumSource.Mode.EXCLUDE)
    void selectedRateHasOneCanonicalResultWithoutCatalogueDuplicates(WorkerInputType type) throws Exception {
        assertThat(validate(type, "ratePerSec: '2500.5'").findings()).isEmpty();
        assertThat(validate(type, "ratePerSec: 0").findings()).isEmpty();
        for (String invalid : List.of("ratePerSec: -1", "ratePerSec: null", "ratePerSec: true", "")) {
            var result = validate(type, invalid);
            assertThat(result.ok()).isFalse();
            assertThat(result.findings()).singleElement().satisfies(finding -> {
                assertThat(finding.path()).endsWith(".inputs." + type.settingsKey() + ".ratePerSec");
                assertThat(finding.message()).contains("finite number >= 0.0");
            });
        }
        var deferred = validate(type, "ratePerSec: \"{{ 3 }}\"");
        assertThat(deferred.ok()).isTrue();
        assertThat(deferred.findings()).singleElement().satisfies(finding -> {
            assertThat(finding.code()).isEqualTo(ValidationIssue.WORK_CONFIGURATION_DEFERRED.code());
            assertThat(finding.path()).endsWith(".inputs." + type.settingsKey() + ".ratePerSec");
        });
    }

    private BundleValidationResult validate(WorkerInputType type, String rate) throws Exception {
        String settings = switch (type) {
            case SCHEDULER -> "maxMessages: 0\n";
            case REDIS_DATASET -> "host: redis\nport: 6379\nssl: false\nlistName: data\nsources: []\npickStrategy: ROUND_ROBIN\n";
            case CSV_DATASET -> "filePath: /data.csv\nrotate: false\nskipHeader: true\ndelimiter: ','\ncharset: UTF-8\n"
                + "startupDelaySeconds: 0\ntickIntervalMs: 1000\n";
            default -> throw new IllegalArgumentException("Test requires a rate-driven input");
        };
        String input = "type: " + type + "\n" + type.settingsKey() + ":\n" + (settings + rate + "\n").indent(2);
        Files.writeString(bundle.resolve("scenario.yaml"), """
            protocolVersion: "2.0.0"
            id: input-rate-validation
            name: Input rate validation
            template:
              image: swarm-controller:latest
              bees:
                - role: generator
                  image: generator:latest
                  work: {}
                  config:
                    message:
                      bodyType: SIMPLE
                      body: data
                    inputs:
            INPUT_SETTINGS
                    outputs:
                      type: NONE
            """.replace("INPUT_SETTINGS\n", input.indent(10)));
        return validator.validate(new BundleValidationInput(BundleValidationSource.SCENARIO_MANAGER,
            bundle, "rate", "rate", null, List.of(), null));
    }
}
