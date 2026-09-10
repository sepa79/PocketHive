package io.pockethive.work.config.csv;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import io.pockethive.work.config.WorkConfigurationMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CsvDatasetParserTest {
    private final CsvDatasetParser parser = new CsvDatasetParser();

    @Test
    void resolvesPropertyTextAndPreservesPathAndRegexSemantics() {
        var fields = valid();
        fields.put("filePath", " /data.csv ");
        fields.put("rotate", "false");
        fields.put("ratePerSec", "2.5");
        fields.put("delimiter", "\\|");
        var settings = parser.parse(fields, "inputs.csv");
        assertThat(settings.filePath()).isEqualTo(" /data.csv ");
        assertThat(settings.rotate()).isFalse();
        assertThat(settings.ratePerSec()).isEqualTo(2.5);
        assertThat(settings.initialDelayMs()).isEqualTo(2000);
        assertThat(settings.delimiter().split("a|b|", -1)).containsExactly("a", "b", "");
        assertThat(settings.charset().name()).isEqualTo("UTF-8");
    }

    @Test
    void rejectsMissingNullUnknownAndInvalidFieldsWithoutExposingValues() {
        for (String field : CsvDatasetParser.FIELDS) {
            var fields = valid();
            fields.remove(field);
            assertThat(parser.validate(fields, "inputs.csv", WorkConfigurationMode.RESOLVED).problems())
                .singleElement().satisfies(problem -> assertThat(problem.path()).isEqualTo("inputs.csv." + field));
            fields.put(field, null);
            assertThatThrownBy(() -> parser.parse(fields, "inputs.csv")).hasMessageContaining("inputs.csv." + field);
        }
        var invalid = Map.of("filePath", 123, "rotate", "yes", "skipHeader", 1, "delimiter", "[",
            "charset", "synthetic-secret-charset", "ratePerSec", -1, "tickIntervalMs", 99,
            "startupDelaySeconds", Long.MAX_VALUE);
        var result = parser.validate(invalid, "inputs.csv", WorkConfigurationMode.RESOLVED);
        assertThat(result.problems()).hasSize(8);
        assertThat(result.settings()).isNull();
        assertThat(result.problems().toString()).doesNotContain("synthetic-secret-charset");
        var unknown = valid(); unknown.put("typo", false);
        assertThatThrownBy(() -> parser.parse(unknown, "inputs.csv")).hasMessageContaining("inputs.csv.typo");
        assertThatThrownBy(() -> parser.parse(List.of(), "inputs.csv")).hasMessageContaining("must be an object");
    }

    @Test
    void defersEachSymbolicFieldOnlyForAuthoring() {
        var fields = valid(); fields.replaceAll((key, value) -> "{{ value }}");
        var authoring = parser.validate(fields, "inputs.csv", WorkConfigurationMode.AUTHORING);
        assertThat(authoring.problems()).isEmpty();
        assertThat(authoring.deferredPaths()).hasSize(8);
        assertThat(authoring.settings()).isNull();
        assertThat(parser.validate(fields, "inputs.csv", WorkConfigurationMode.RESOLVED).problems()).hasSize(8);
    }

    @Test
    void mergesByPresenceAndDoesNotChangeBaseOnRejection() {
        var base = parser.parse(valid(), "inputs.csv");
        var patch = new LinkedHashMap<String, Object>();
        patch.put("ratePerSec", 20); patch.put("rotate", null);
        assertThatThrownBy(() -> parser.merge(base, patch, "inputs.csv")).hasMessageContaining("inputs.csv.rotate");
        assertThat(base.ratePerSec()).isEqualTo(1);
        assertThat(parser.merge(base, Map.of("ratePerSec", 20), "inputs.csv").ratePerSec()).isEqualTo(20);
    }

    private static Map<String, Object> valid() {
        return new LinkedHashMap<>(Map.of("filePath", "/data.csv", "ratePerSec", 1, "rotate", false,
            "skipHeader", true, "delimiter", ",", "charset", "UTF-8", "startupDelaySeconds", 2, "tickIntervalMs", 1000));
    }
}
