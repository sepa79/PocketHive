package io.pockethive.work.local.csv;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;

class CsvDatasetCursorTest {
    @TempDir Path directory;

    @Test
    void readsConfiguredCharsetAndRegexDelimiterWithTrimmedHeaderFields() throws Exception {
        Path file = directory.resolve("records.csv");
        Files.writeString(file, " name | value | empty \n\n André | 1 |\n second | 2 | |ignored\n short\n",
            StandardCharsets.ISO_8859_1);
        var cursor = cursor();
        cursor.load(settings(file, true));

        assertThat(cursor.size()).isEqualTo(3);
        assertThat(cursor.rowJson(cursor.nextRowIndex(false)))
            .isEqualTo("{\"name\":\"André\",\"value\":\"1\",\"empty\":\"\"}");
        assertThat(cursor.remaining()).isEqualTo(2);
        assertThat(cursor.rowJson(cursor.nextRowIndex(false)))
            .isEqualTo("{\"name\":\"second\",\"value\":\"2\",\"empty\":\"\"}");
        assertThat(cursor.rowJson(cursor.nextRowIndex(false))).isEqualTo("{\"name\":\"short\"}");
        assertThat(cursor.remaining()).isZero();
    }

    @Test
    void headerlessRowsKeepTrailingEmptyColumnsAndEscapeJson() throws Exception {
        Path file = directory.resolve("records.csv");
        Files.writeString(file, " \n first | \"quoted\" |\n");
        var cursor = cursor();
        cursor.load(settings(file, false));

        assertThat(cursor.size()).isEqualTo(1);
        assertThat(cursor.rowJson(cursor.nextRowIndex(false)))
            .isEqualTo("{\"col0\":\"first\",\"col1\":\"\\\"quoted\\\"\",\"col2\":\"\"}");
    }

    @Test
    void duplicateHeaderKeepsLastValue() throws Exception {
        Path file = directory.resolve("records.csv");
        Files.writeString(file, "name| name \nfirst|last\n");
        var cursor = cursor();
        cursor.load(settings(file, true));
        assertThat(cursor.rowJson(cursor.nextRowIndex(false))).isEqualTo("{\"name\":\"last\"}");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void preservesSelectionPositionOnReloadAndRetainsEofAndRotation(boolean rotate) throws Exception {
        Path file = directory.resolve("records.csv");
        Files.writeString(file, "one\ntwo\n");
        var cursor = cursor();
        var settings = settings(file, false);
        cursor.load(settings);
        assertThat(cursor.rowJson(cursor.nextRowIndex(rotate))).isEqualTo("{\"col0\":\"one\"}");
        Files.writeString(file, "changed-one\nchanged-two\n");
        cursor.load(settings);
        assertThat(cursor.rowJson(cursor.nextRowIndex(rotate))).isEqualTo("{\"col0\":\"changed-two\"}");
        assertThat(cursor.remaining()).isZero();
        assertThat(cursor.nextRowIndex(rotate)).isEqualTo(rotate ? 0 : -1);
        assertThat(cursor.position()).isEqualTo(rotate ? 1 : 3);
        assertThat(cursor.nextRowIndex(rotate)).isEqualTo(rotate ? 1 : -1);
        assertThat(cursor.position()).isEqualTo(rotate ? 2 : 4);
    }

    @Test
    void reportsMissingEmptyAndHeaderOnlyFiles() throws Exception {
        Path file = directory.resolve("records.csv");
        var cursor = cursor();
        var settings = settings(file, true);
        assertThatThrownBy(() -> cursor.load(settings)).isInstanceOf(IllegalStateException.class)
            .hasMessage("CSV file not found: " + file);
        Files.writeString(file, " \n\n");
        assertThatThrownBy(() -> cursor.load(settings)).isInstanceOf(IllegalStateException.class)
            .hasMessage("CSV file is empty: " + file);
        Files.writeString(file, "name|value\n");
        assertThatThrownBy(() -> cursor.load(settings)).isInstanceOf(IllegalStateException.class)
            .hasMessage("CSV has only 1 row but skipHeader=true (need at least 2 rows)");
    }

    private CsvDatasetCursor cursor() {
        return new CsvDatasetCursor("test-csv", LoggerFactory.getLogger(CsvDatasetCursorTest.class));
    }

    private CsvDatasetSettings settings(Path file, boolean skipHeader) {
        return new CsvDatasetParser().parse(Map.of(
            "filePath", file.toString(), "ratePerSec", 1.0, "rotate", false,
            "skipHeader", skipHeader, "delimiter", "\\|", "charset", "ISO-8859-1",
            "startupDelaySeconds", 0, "tickIntervalMs", 1000), CsvDatasetParser.PATH);
    }
}
