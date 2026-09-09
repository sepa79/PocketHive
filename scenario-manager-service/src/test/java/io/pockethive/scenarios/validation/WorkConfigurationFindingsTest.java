package io.pockethive.scenarios.validation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WorkConfigurationFindingsTest {
    private final WorkConfigurationFindings projection = new WorkConfigurationFindings();

    @Test
    void retainsTheRouteErrorPathAndMessageInScenarioDiagnostics() {
        var findings = new ArrayList<ValidationFinding>();
        projection.redisOutputTargets(List.of(Map.of("header", "key", "list", "out")), null, null,
            "scenario.yaml:template.bees[0].config.outputs.redis", findings);

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
        var result = projection.redisOutputTargets("{{ vars.routes }}", null, null, "outputs.redis", findings);
        assertThat(result.deferredPaths()).containsExactly("outputs.redis.routes");
        assertThat(findings)
            .singleElement().satisfies(finding -> {
                assertThat(finding.code()).isEqualTo(ValidationIssue.WORK_CONFIGURATION_DEFERRED.code());
                assertThat(finding.severity()).isEqualTo(ValidationSeverity.WARNING);
                assertThat(finding.path()).isEqualTo("outputs.redis.routes");
                assertThat(finding.fix()).contains("validate the resolved configuration");
            });
    }
}
