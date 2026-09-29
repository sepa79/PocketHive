package io.pockethive.clearingexport;

import io.pockethive.controlplane.filesystem.RuntimeOutputDirectory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class LocalDirectoryClearingExportSinkTest {

  @TempDir
  Path tempDir;

  @Test
  void writesFileAndAppendsManifestWhenEnabled() throws Exception {
    LocalDirectoryClearingExportSink sink = new LocalDirectoryClearingExportSink(new RuntimeOutputDirectory(tempDir));

    ClearingExportWorkerConfig config = new ClearingExportWorkerConfig(
        "template",
        false,
        21_600_000L,
        10,
        1_000,
        100,
        true,
        "\n",
        "out.dat",
        "H",
        "D",
        "T",
        ".tmp",
        true,
        "reports/clearing/manifest.jsonl",
        "/tmp/schemas",
        null,
        null
    );

    ClearingRenderedFile file = new ClearingRenderedFile(
        "settlement_001.dat",
        "H\nD\nT\n",
        1,
        Instant.parse("2026-02-18T10:00:00Z")
    );

    sink.writeFile(config, file);

    Path finalFile = tempDir.resolve("settlement_001.dat");
    assertThat(Files.exists(finalFile)).isTrue();
    assertThat(Files.readString(finalFile)).isEqualTo("H\nD\nT\n");

    Path manifest = tempDir.resolve("reports/clearing/manifest.jsonl");
    assertThat(Files.exists(manifest)).isTrue();
    String manifestText = Files.readString(manifest);
    assertThat(manifestText).contains("\"fileName\":\"settlement_001.dat\"");
    assertThat(manifestText).contains("\"recordCount\":1");
  }

  @Test
  void finalizeStreamingIsIdempotentAndDoesNotDuplicateFooter() throws Exception {
    LocalDirectoryClearingExportSink sink = new LocalDirectoryClearingExportSink(new RuntimeOutputDirectory(tempDir));
    ClearingExportWorkerConfig config = new ClearingExportWorkerConfig(
        "template",
        true,
        21_600_000L,
        10,
        1_000,
        100,
        true,
        "\n",
        "stream.dat",
        "H",
        "D",
        "T",
        ".tmp",
        false,
        "reports/clearing/manifest.jsonl",
        "/tmp/schemas",
        null,
        null
    );

    sink.openStreamingFile(config, "stream.dat", "H|x", "\n");
    sink.appendStreamingRecord(config, "stream.dat", "D|one", "\n");
    sink.finalizeStreamingFile(
        config,
        "stream.dat",
        "T|1",
        "\n",
        1,
        Instant.parse("2026-02-21T10:00:00Z"));
    sink.finalizeStreamingFile(
        config,
        "stream.dat",
        "T|1",
        "\n",
        1,
        Instant.parse("2026-02-21T10:00:01Z"));

    String content = Files.readString(tempDir.resolve("stream.dat"));
    assertThat(content).isEqualTo("H|x\nD|one\nT|1\n");
  }

  @Test
  void finalizeStreamingMovesTempFileAtomicallyToFinalLocation() throws Exception {
    LocalDirectoryClearingExportSink sink = new LocalDirectoryClearingExportSink(new RuntimeOutputDirectory(tempDir));
    ClearingExportWorkerConfig config = new ClearingExportWorkerConfig(
        "template",
        true,
        21_600_000L,
        10,
        1_000,
        100,
        true,
        "\n",
        "stream.dat",
        "H",
        "D",
        "T",
        ".tmp",
        false,
        "reports/clearing/manifest.jsonl",
        "/tmp/schemas",
        null,
        null
    );

    sink.openStreamingFile(config, "stream.dat", "H|x", "\n");
    sink.appendStreamingRecord(config, "stream.dat", "D|one", "\n");
    sink.finalizeStreamingFile(
        config,
        "stream.dat",
        "T|1",
        "\n",
        1,
        Instant.parse("2026-02-21T10:00:00Z"));

    assertThat(Files.exists(tempDir.resolve("stream.dat.tmp"))).isFalse();
    assertThat(Files.exists(tempDir.resolve("stream.dat"))).isTrue();
    assertThat(Files.readString(tempDir.resolve("stream.dat"))).isEqualTo("H|x\nD|one\nT|1\n");
  }
  @ParameterizedTest
  @ValueSource(strings = {"../outside.dat", "/tmp/outside.dat"})
  void rejectsEscapingFileNamesBeforeWriting(String name) {
    Path output = tempDir.resolve("outputs");
    var sink = new LocalDirectoryClearingExportSink(
        new RuntimeOutputDirectory(output));
    var config = manifestConfig("reports/manifest.jsonl");
    var file = new ClearingRenderedFile(name, "content", 1, Instant.EPOCH);

    assertThatThrownBy(() -> sink.writeFile(config, file)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> sink.openStreamingFile(config, name, "H", "\n"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(output).doesNotExist();
  }

  @ParameterizedTest
  @ValueSource(strings = {"../outside.jsonl", "/tmp/outside.jsonl"})
  void rejectsEscapingManifestBeforeWritingBatchOrOpeningStream(String manifest) {
    Path output = tempDir.resolve("outputs");
    var sink = new LocalDirectoryClearingExportSink(
        new RuntimeOutputDirectory(output));
    var config = manifestConfig(manifest);
    var file = new ClearingRenderedFile("clearing.dat", "content", 1, Instant.EPOCH);

    assertThatThrownBy(() -> sink.writeFile(config, file)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> sink.openStreamingFile(config, "clearing.dat", "H", "\n"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(output).doesNotExist();
  }

  private ClearingExportWorkerConfig manifestConfig(String manifest) {
    return new ClearingExportWorkerConfig("template", false, 0L, 10, 1000L, 100,
        true, "\n", "out.dat", "H", "D", "T", ".tmp", true, manifest,
        "/tmp/schemas", null, null);
  }
}
