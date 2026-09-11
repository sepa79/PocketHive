package io.pockethive.scenarios.validation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WorkConfigurationFindingsTest {
    private final WorkConfigurationFindings projection = new WorkConfigurationFindings(new io.pockethive.work.config.composition.CurrentWorkConfigurationProviders().workConfigurationParser());

    @Test
    void retainsTheRouteErrorPathAndMessageInScenarioDiagnostics() {
        var findings = new ArrayList<ValidationFinding>();
        projection.validate(config(List.of(Map.of("header", "key", "list", "out"))),
            "scenario.yaml:template.bees[0].config", findings);

        assertThat(findings).singleElement().satisfies(finding -> {
            assertThat(finding.code()).isEqualTo(ValidationIssue.SCENARIO_DESCRIPTOR_INVALID.code());
            assertThat(finding.severity()).isEqualTo(ValidationSeverity.ERROR);
            assertThat(finding.path()).isEqualTo("scenario.yaml:template.bees[0].config.outputs.redis.routes[0].headerMatch");
            assertThat(finding.message()).isEqualTo("Redis output route headerMatch must be configured when header is set.");
        });
    }

    @Test
    void exposesDeferredValidationAsAnAuthoringWarning() {
        var findings = new ArrayList<ValidationFinding>();
        projection.validate(config("{{ vars.routes }}"), "config", findings);
        assertThat(findings)
            .singleElement().satisfies(finding -> {
                assertThat(finding.code()).isEqualTo(ValidationIssue.WORK_CONFIGURATION_DEFERRED.code());
                assertThat(finding.severity()).isEqualTo(ValidationSeverity.WARNING);
                assertThat(finding.path()).isEqualTo("config.outputs.redis.routes");
                assertThat(finding.fix()).contains("validate the resolved configuration");
            });
    }
    @Test
    void rabbitAuthoringUsesTuningWithoutDeferredTopology() {
        var findings = new ArrayList<ValidationFinding>();
        projection.validate(Map.of("inputs", Map.of("type", "RABBITMQ"),
            "outputs", Map.of("type", "RABBITMQ", "rabbit", Map.of("persistent", false))), "config", findings);
        assertThat(findings).isEmpty();
    }

    @Test
    void rabbitPhysicalAddressIsRejectedWithCanonicalScenarioPath() {
        var findings = new ArrayList<ValidationFinding>();
        projection.validate(Map.of("inputs", Map.of("type", "RABBITMQ", "rabbit", Map.of("queue", "{{ address }}")),
            "outputs", Map.of("type", "NONE")), "scenario.yaml:template.bees[0].config", findings);
        assertThat(findings).singleElement().satisfies(finding -> {
            assertThat(finding.severity()).isEqualTo(ValidationSeverity.ERROR);
            assertThat(finding.path()).isEqualTo("scenario.yaml:template.bees[0].config.inputs.rabbit.queue");
            assertThat(finding.message()).contains("owned by topology");
        });
    }

    private Map<String, Object> config(Object routes) {
        return Map.of("inputs", Map.of("type", "RABBITMQ"), "outputs", Map.of("type", "REDIS", "redis",
            Map.of("host", "redis", "port", 6379, "ssl", false, "sourceStep", "LAST",
                "pushDirection", "RPUSH", "maxLen", -1, "routes", routes)));
    }
}
