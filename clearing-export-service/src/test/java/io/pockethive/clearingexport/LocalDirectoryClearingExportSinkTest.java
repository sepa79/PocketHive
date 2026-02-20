package io.pockethive.clearingexport;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class LocalDirectoryClearingExportSinkTest {

  @TempDir
  Path tempDir;

  @Test
  void writesFileAndAppendsManifestWhenEnabled() throws Exception {
    LocalDirectoryClearingExportSink sink = new LocalDirectoryClearingExportSink();

    ClearingExportWorkerConfig config = new ClearingExportWorkerConfig(
        "template",
        10,
        1_000,
        100,
        true,
        "\n",
        "out.dat",
        "H",
        "D",
        "T",
        tempDir.toString(),
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
}
