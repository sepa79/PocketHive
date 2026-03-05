package io.pockethive.clearingexport;

import io.pockethive.worker.sdk.templating.PebbleTemplateRenderer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.LongSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ClearingExportBatchWriterStreamingTest {

  @TempDir
  Path tempDir;

  @Test
  void streamingModeFinalizesByTimeWindowWithoutBufferingWholeBatch() throws Exception {
    TestClock clock = new TestClock(1_000L);
    ClearingExportFileAssembler assembler =
        new ClearingExportFileAssembler(new PebbleTemplateRenderer(), new XmlOutputFormatter());
    ClearingExportBatchWriter writer =
        new ClearingExportBatchWriter(assembler, new LocalDirectoryClearingExportSink(), clock, false);

    ClearingExportWorkerConfig config = new ClearingExportWorkerConfig(
        "template",
        true,
        20,
        1_000_000,
        1_000,
        1_000_000,
        true,
        "\n",
        "stream.dat",
        "H|{{ now }}",
        "D|{{ steps.selected.payload }}",
        "T|{{ recordCount }}",
        tempDir.toString(),
        ".tmp",
        false,
        "reports/clearing/manifest.jsonl",
        "/tmp/schemas",
        null,
        null
    );

    writer.append("D|one", config);
    writer.append("D|two", config);

    clock.advanceBy(40L);
    writer.flushIfDue();

    assertThat(writer.filesWritten()).isEqualTo(1L);
    assertThat(writer.bufferedRecords()).isEqualTo(0L);
    assertThat(writer.lastFileRecordCount()).isEqualTo(2L);

    Path finalFile = tempDir.resolve("stream.dat");
    assertThat(Files.exists(finalFile)).isTrue();
    String content = Files.readString(finalFile);
    assertThat(content).contains("H|");
    assertThat(content).contains("D|one");
    assertThat(content).contains("D|two");
    assertThat(content).contains("T|2");
  }

  @Test
  void preflightFailsWhenSinkDoesNotSupportStreaming() {
    TestClock clock = new TestClock(1_000L);
    ClearingExportFileAssembler assembler =
        new ClearingExportFileAssembler(new PebbleTemplateRenderer(), new XmlOutputFormatter());
    ClearingExportSink sink = new ClearingExportSink() {
      @Override
      public ClearingExportSinkWriteResult writeFile(ClearingExportWorkerConfig config, ClearingRenderedFile file) {
        return new ClearingExportSinkWriteResult(file.fileName(), file.recordCount(), file.bytesUtf8(), file.createdAt(), "memory://test");
      }
    };
    ClearingExportBatchWriter writer = new ClearingExportBatchWriter(assembler, sink, clock, false);

    ClearingExportWorkerConfig config = new ClearingExportWorkerConfig(
        "template",
        true,
        20,
        1_000_000,
        1_000,
        1_000_000,
        true,
        "\n",
        "stream.dat",
        "H|{{ now }}",
        "D|{{ steps.selected.payload }}",
        "T|{{ recordCount }}",
        tempDir.toString(),
        ".tmp",
        false,
        "reports/clearing/manifest.jsonl",
        "/tmp/schemas",
        null,
        null
    );

    assertThatThrownBy(() -> writer.preflight(config))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("does not support streaming append mode");
  }

  private static final class TestClock implements LongSupplier {
    private long nowMs;

    private TestClock(long initialMs) {
      this.nowMs = initialMs;
    }

    @Override
    public long getAsLong() {
      return nowMs;
    }

    private void advanceBy(long millis) {
      nowMs += millis;
    }
  }
}
