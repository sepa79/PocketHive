package io.pockethive.scenarios;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.scenarios.validation.BundleValidationResult;
import io.pockethive.scenarios.validation.BundleValidationSource;
import io.pockethive.scenarios.validation.ValidationCategory;
import io.pockethive.scenarios.validation.ValidationFinding;
import io.pockethive.scenarios.validation.ValidationSeverity;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class BundleValidationResultContractTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void uiValidationMirrorMatchesScenarioManagerWireShape() throws IOException {
        BundleValidationResult result = BundleValidationResult.of(
                BundleValidationSource.UPLOADED_ZIP,
                "bundles/demo",
                "bundles/demo",
                "demo",
                List.of(
                        new ValidationFinding(
                                ValidationCategory.SCENARIO,
                                "SCENARIO_DESCRIPTOR_INVALID",
                                ValidationSeverity.ERROR,
                                "scenario.yaml",
                                "Invalid scenario descriptor.",
                                "Repair scenario.yaml."),
                        new ValidationFinding(
                                ValidationCategory.VARIABLES,
                                "VARIABLE_REFERENCE_UNKNOWN",
                                ValidationSeverity.WARNING,
                                "variables.yaml",
                                "Unknown variable reference.",
                                "Add the missing variable definition.")));

        JsonNode json = mapper.valueToTree(result);

        assertThat(json.get("ok").asBoolean()).isFalse();
        assertThat(json.get("source").asText()).isEqualTo("uploaded-zip");
        assertThat(json.get("scenarioId").asText()).isEqualTo("demo");
        assertThat(fieldNames(json.get("summary"))).containsExactlyInAnyOrder("errors", "warnings");
        assertThat(json.at("/summary/errors").asInt()).isEqualTo(1);
        assertThat(json.at("/summary/warnings").asInt()).isEqualTo(1);
        assertThat(json.at("/findings/0/severity").asText()).isEqualTo("error");
        assertThat(json.at("/findings/1/severity").asText()).isEqualTo("warning");
        assertThat(fieldNames(json.at("/findings/0"))).containsExactlyInAnyOrder(
                "category",
                "code",
                "severity",
                "path",
                "message",
                "fix");

        String uiApi = Files.readString(repoFile("ui-v2/src/lib/scenariosApi.ts"));
        String severityType = between(
                uiApi,
                "export type BundleValidationSeverity",
                "export type BundleValidationFinding");
        assertThat(severityType).contains("'error' | 'warning'");
        assertThat(severityType).doesNotContain("'info'");

        String findingType = between(
                uiApi,
                "export type BundleValidationFinding",
                "export type BundleValidationResult");
        assertThat(findingType)
                .contains("category: string")
                .contains("code: string")
                .contains("severity: BundleValidationSeverity")
                .contains("path: string")
                .contains("message: string")
                .contains("fix: string");

        String resultType = between(
                uiApi,
                "export type BundleValidationResult",
                "function normalizeScenarioSummary");
        assertThat(resultType)
                .contains("ok: boolean")
                .contains("source: string")
                .contains("bundleKey: string | null")
                .contains("bundlePath: string | null")
                .contains("scenarioId: string | null")
                .contains("errors: number")
                .contains("warnings: number")
                .contains("findings: BundleValidationFinding[]");
        assertThat(resultType).doesNotContain("infos").doesNotContain("'info'");
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private static Path repoFile(String relativePath) {
        Path cwd = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        for (Path candidate : List.of(cwd.resolve(relativePath), cwd.resolve("..").resolve(relativePath).normalize())) {
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("Repository file not found: " + relativePath);
    }

    private static String between(String source, String startMarker, String endMarker) {
        int start = source.indexOf(startMarker);
        int end = source.indexOf(endMarker, start + startMarker.length());
        assertThat(start).as("start marker %s", startMarker).isNotNegative();
        assertThat(end).as("end marker %s", endMarker).isGreaterThan(start);
        return source.substring(start, end);
    }
}
