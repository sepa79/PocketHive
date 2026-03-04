package io.pockethive.worker.sdk.input.csv;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class CsvDataSetWorkInputPathTest {

    @Test
    void resolveCsvPathResolvesRelativePathWithinScenarioRoot() {
        String resolved = CsvDataSetWorkInput.resolveCsvPath("datasets/input.csv");

        assertThat(resolved).isEqualTo("/app/scenario/datasets/input.csv");
    }

    @Test
    void resolveCsvPathAllowsAbsolutePathWithinScenarioRoot() {
        String resolved = CsvDataSetWorkInput.resolveCsvPath("/app/scenario/data/input.csv");

        assertThat(resolved).isEqualTo("/app/scenario/data/input.csv");
    }

    @Test
    void resolveCsvPathRejectsRelativeTraversalOutsideScenarioRoot() {
        assertThatThrownBy(() -> CsvDataSetWorkInput.resolveCsvPath("../../etc/passwd"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("must stay within /app/scenario");
    }

    @Test
    void resolveCsvPathRejectsAbsolutePathOutsideScenarioRoot() {
        assertThatThrownBy(() -> CsvDataSetWorkInput.resolveCsvPath("/etc/passwd"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("must stay within /app/scenario");
    }
}
