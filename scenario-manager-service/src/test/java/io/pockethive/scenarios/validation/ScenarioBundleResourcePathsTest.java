package io.pockethive.scenarios.validation;

import io.pockethive.scenarios.ScenarioBundleLayout;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.assertThat;

class ScenarioBundleResourcePathsTest {
    private static final String FIELD = "scenario.yaml:template.bees.target.config.templateRoot";

    @ParameterizedTest
    @ValueSource(strings = {"{{ vars.resourceRoot }}", "/app/scenario/{{ vars.directory }}"})
    void bootstrapRootsDeferBeforeConcreteMapping(String location) {
        var findings = new ArrayList<ValidationFinding>();
        assertThat(ScenarioBundleResourcePaths.relativeRoot(location, FIELD, findings)).isEmpty();
        assertThat(findings).singleElement().satisfies(finding -> {
            assertThat(finding.code()).isEqualTo("WORK_CONFIGURATION_DEFERRED");
            assertThat(finding.severity()).isEqualTo(ValidationSeverity.WARNING);
            assertThat(finding.path()).isEqualTo(FIELD);
        });
    }

    @ParameterizedTest
    @CsvSource({"/app/scenario/..clearing-schemas,..clearing-schemas", "/app/scenario/..templates,..templates",
        "/app/scenario/nested/../..data,..data"})
    void containedDotPrefixedNamesAreNotParentTraversal(String location, String expected) {
        var findings = new ArrayList<ValidationFinding>();
        assertThat(ScenarioBundleResourcePaths.relativeRoot(location, FIELD, findings)).contains(expected);
        assertThat(findings).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/app/scenario/..", "/app/scenario/../outside", "/app/scenario/nested/../../outside"})
    void parentTraversalStillFailsAfterNormalization(String location) {
        var findings = new ArrayList<ValidationFinding>();
        assertThat(ScenarioBundleResourcePaths.relativeRoot(location, FIELD, findings)).isEmpty();
        assertThat(findings).singleElement().satisfies(finding -> {
            assertThat(finding.code()).isEqualTo("TEMPLATE_INVALID");
            assertThat(finding.severity()).isEqualTo(ValidationSeverity.ERROR);
        });
    }

    @Test
    void concreteRootsKeepTheirExistingMappingAndContainmentPolicy() {
        var findings = new ArrayList<ValidationFinding>();
        assertThat(ScenarioBundleResourcePaths.relativeRoot(ScenarioBundleLayout.CONTAINER_ROOT, FIELD, findings))
            .contains("");
        assertThat(ScenarioBundleResourcePaths.relativeRoot(" /app/scenario//templates/ ", FIELD, findings))
            .contains("templates");
        assertThat(ScenarioBundleResourcePaths.relativeRoot("/opt/shared/templates", FIELD, findings)).isEmpty();
        assertThat(ScenarioBundleResourcePaths.relativeRoot("templates", FIELD, findings)).isEmpty();
        assertThat(ScenarioBundleResourcePaths.relativeRoot(null, FIELD, findings)).isEmpty();
        assertThat(findings).isEmpty();
        assertThat(ScenarioBundleResourcePaths.relativeRoot("/app/scenario/../outside", FIELD, findings)).isEmpty();
        assertThat(findings).singleElement().satisfies(finding -> {
            assertThat(finding.code()).isEqualTo("TEMPLATE_INVALID");
            assertThat(finding.severity()).isEqualTo(ValidationSeverity.ERROR);
        });
    }
}
