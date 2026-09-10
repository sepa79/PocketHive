package io.pockethive.work.config.csv;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CsvDatasetEnvironmentTest {
    @Test
    void overridesAreExplicitAndExportDoesNotEraseInvalidDeclarationTypes() {
        var declared = new LinkedHashMap<String, Object>();
        declared.put("filePath", 123);
        declared.put("rotate", null);
        declared.put("skipHeader", false);
        declared.put("ratePerSec", 3.0);
        declared.put("delimiter", "${CSV_DELIMITER}");
        var codec = new CsvDatasetEnvironment();
        var candidate = codec.candidate(Map.of("csv", declared),
            Map.of("pockethive.inputs.csv.file-path", "/override.csv", "pockethive.inputs.csv.rotate", "")::get);
        assertThat(candidate).containsEntry("filePath", "/override.csv").containsEntry("rotate", "");
        assertThat(codec.encode(candidate)).containsEntry("POCKETHIVE_INPUTS_CSV_RATEPERSEC", "3")
            .containsEntry("POCKETHIVE_INPUTS_CSV_SKIPHEADER", "false")
            .containsEntry("POCKETHIVE_INPUTS_CSV_DELIMITER", "${CSV_DELIMITER}")
            .containsEntry("POCKETHIVE_INPUTS_CSV_ROTATE", "");
        assertThat(declared).containsEntry("filePath", 123).containsEntry("rotate", null);
        var withoutOverrides = codec.candidate(Map.of("csv", declared), ignored -> null);
        assertThat(withoutOverrides).containsEntry("filePath", 123).containsEntry("rotate", null);
    }
}
