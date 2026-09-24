package io.pockethive.orchestrator.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.controlplane.filesystem.RuntimeFilesystemLayout;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileSwarmJournalReaderTest {
    @TempDir Path root;

    @Test
    void preservesAppendOrderAndPayloadWhileSkippingMalformedAndBlankLines() throws IOException {
        write("run-1", """
            {"severity":"INFO","type":"first","data":{"ok":true}}

            broken-json
            ["wrong-shape"]
            {"severity":" warn ","type":"second"}
            {"type":"third"}
            """);
        var entries = reader().read("alpha", "run-1", null);
        assertThat(entries).extracting(entry -> entry.get("type"))
            .containsExactly("first", "second", "third");
        assertThat(entries.getFirst().get("data")).isEqualTo(java.util.Map.of("ok", true));
        assertThat(reader().read("alpha", "run-1", "WARN"))
            .extracting(entry -> entry.get("type")).containsExactly("second");
        assertThat(reader().read("alpha", "run-1", "ERROR")).isEmpty();
    }

    @Test
    void distinguishesMissingEmptyAndNonFileJournalsWithoutCreatingAnything() throws IOException {
        assertThat(reader().read("alpha", "missing", null)).isNull();
        assertThat(root.resolve("alpha")).doesNotExist();
        write("empty", "");
        assertThat(reader().read("alpha", "empty", null)).isEmpty();
        Files.createDirectories(layout().swarmJournalFile("alpha", "directory"));
        assertThat(reader().read("alpha", "directory", null)).isNull();
    }

    @Test
    void selectsMostRecentDirectoryEvenWithoutAJournalAndIgnoresPlainFiles() throws IOException {
        write("older", "{}");
        Path newer = Files.createDirectories(layout().swarmRunDirectory("alpha", "newer"));
        Files.setLastModifiedTime(layout().swarmRunDirectory("alpha", "older"), FileTime.fromMillis(1000));
        Files.setLastModifiedTime(newer, FileTime.fromMillis(2000));
        Files.writeString(layout().swarmRoot("alpha").resolve("latest-file"), "ignored");
        assertThat(reader().latestRunDirectory("alpha")).isEqualTo("newer");
        assertThat(reader().read("alpha", "newer", null)).isNull();
        assertThat(reader().latestRunDirectory("absent")).isNull();
    }

    @Test
    void doesNotMixRunsOrSwarms() throws IOException {
        write("run-1", "{\"type\":\"first\"}");
        write("run-2", "{\"type\":\"second\"}");
        assertThat(reader().read("alpha", "run-1", null)).extracting(e -> e.get("type"))
            .containsExactly("first");
        assertThat(reader().read("alpha", "run-2", null)).extracting(e -> e.get("type"))
            .containsExactly("second");
        assertThat(reader().read("beta", "run-1", null)).isNull();
        assertThatThrownBy(() -> reader().read("alpha", "../run-1", null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void retainsExistingJsonNullFailureWithoutSeverityAndSkipWithSeverity() throws IOException {
        write("run-1", "null\n{\"severity\":\"INFO\"}\n");
        assertThatThrownBy(() -> reader().read("alpha", "run-1", null))
            .isInstanceOf(NullPointerException.class);
        assertThat(reader().read("alpha", "run-1", "INFO")).hasSize(1);
    }

    private void write(String run, String contents) throws IOException {
        Path file = layout().swarmJournalFile("alpha", run);
        Files.createDirectories(file.getParent());
        Files.writeString(file, contents);
    }

    private RuntimeFilesystemLayout layout() {
        return RuntimeFilesystemLayout.of(root.toString(), "/runtime");
    }

    private FileSwarmJournalReader reader() {
        return new FileSwarmJournalReader(new ObjectMapper(), layout());
    }
}
