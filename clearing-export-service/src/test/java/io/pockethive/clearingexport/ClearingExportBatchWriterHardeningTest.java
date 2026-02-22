package io.pockethive.clearingexport;

import io.pockethive.worker.sdk.templating.PebbleTemplateRenderer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ClearingExportBatchWriterHardeningTest {

  @Test
  void flushesByRecordCountThreshold() throws Exception {
    RecordingSink sink = new RecordingSink(0);
    ClearingExportBatchWriter writer = newWriter(sink);
    ClearingExportWorkerConfig config = config(2, 60_000L);

    writer.append("D|one", config);
    assertThat(writer.filesWritten()).isEqualTo(0L);
    assertThat(writer.bufferedRecords()).isEqualTo(1L);

    writer.append("D|two", config);

    assertThat(writer.filesWritten()).isEqualTo(1L);
    assertThat(writer.filesFailed()).isEqualTo(0L);
    assertThat(writer.bufferedRecords()).isEqualTo(0L);
    assertThat(writer.lastFileRecordCount()).isEqualTo(2L);
    assertThat(writer.lastFileName()).isEqualTo("test.dat");
    assertThat(sink.writes.get()).isEqualTo(1);
  }

  @Test
  void flushesByTimeIntervalWhenBelowCountThreshold() throws Exception {
    RecordingSink sink = new RecordingSink(0);
    ClearingExportBatchWriter writer = newWriter(sink);
    ClearingExportWorkerConfig config = config(100, 20L);

    writer.append("D|one", config);
    assertThat(writer.filesWritten()).isEqualTo(0L);
    assertThat(writer.bufferedRecords()).isEqualTo(1L);

    Thread.sleep(35L);
    writer.flushIfDue();

    assertThat(writer.filesWritten()).isEqualTo(1L);
    assertThat(writer.bufferedRecords()).isEqualTo(0L);
    assertThat(writer.lastFileRecordCount()).isEqualTo(1L);
    assertThat(sink.writes.get()).isEqualTo(1);
  }

  @Test
  void keepsBatchAfterWriteFailureAndRetriesOnNextTrigger() throws Exception {
    RecordingSink sink = new RecordingSink(1);
    ClearingExportBatchWriter writer = newWriter(sink);
    ClearingExportWorkerConfig config = config(2, 60_000L);

    writer.append("D|one", config);
    assertThatThrownBy(() -> writer.append("D|two", config))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("simulated sink failure");

    assertThat(writer.filesWritten()).isEqualTo(0L);
    assertThat(writer.filesFailed()).isEqualTo(1L);
    assertThat(writer.bufferedRecords()).isEqualTo(2L);

    writer.flushIfDue();

    assertThat(writer.filesWritten()).isEqualTo(1L);
    assertThat(writer.filesFailed()).isEqualTo(1L);
    assertThat(writer.bufferedRecords()).isEqualTo(0L);
    assertThat(writer.lastFileRecordCount()).isEqualTo(2L);
    assertThat(sink.writes.get()).isEqualTo(2);
  }

  @Test
  void emitsCreatedAndFlushSummaryLifecycleEventsOnSuccessfulFlush() throws Exception {
    RecordingSink sink = new RecordingSink(0);
    ClearingExportBatchWriter writer = newWriter(sink);
    List<ClearingExportBatchWriter.ClearingExportLifecycleEventType> events = new ArrayList<>();
    writer.setLifecycleListener(event -> events.add(event.type()));
    ClearingExportWorkerConfig config = config(2, 60_000L);

    writer.append("D|one", config);
    writer.append("D|two", config);

    assertThat(events).containsExactly(
        ClearingExportBatchWriter.ClearingExportLifecycleEventType.CREATED,
        ClearingExportBatchWriter.ClearingExportLifecycleEventType.FLUSH_SUMMARY
    );
  }

  @Test
  void emitsWriteFailedLifecycleEventWhenBatchWriteFails() throws Exception {
    RecordingSink sink = new RecordingSink(1);
    ClearingExportBatchWriter writer = newWriter(sink);
    List<ClearingExportBatchWriter.ClearingExportLifecycleEventType> events = new ArrayList<>();
    writer.setLifecycleListener(event -> events.add(event.type()));
    ClearingExportWorkerConfig config = config(2, 60_000L);

    writer.append("D|one", config);
    assertThatThrownBy(() -> writer.append("D|two", config))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("simulated sink failure");

    assertThat(events).contains(ClearingExportBatchWriter.ClearingExportLifecycleEventType.WRITE_FAILED);
  }

  @Test
  void emitsFinalizeFailedLifecycleEventWhenStreamingFinalizeFails() throws Exception {
    StreamingFinalizeFailingSink sink = new StreamingFinalizeFailingSink();
    ClearingExportBatchWriter writer = newWriter(sink);
    List<ClearingExportBatchWriter.ClearingExportLifecycleEventType> events = new ArrayList<>();
    writer.setLifecycleListener(event -> events.add(event.type()));
    ClearingExportWorkerConfig config = streamingConfig(15L);

    writer.append("D|one", config);
    Thread.sleep(30L);
    assertThatThrownBy(() -> writer.flushIfDue())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("simulated finalize failure");

    assertThat(events).containsExactly(ClearingExportBatchWriter.ClearingExportLifecycleEventType.FINALIZE_FAILED);
  }

  @Test
  void emitsWriteFailedLifecycleEventWhenStreamingOpenFails() {
    StreamingOpenFailingSink sink = new StreamingOpenFailingSink();
    ClearingExportBatchWriter writer = newWriter(sink);
    List<ClearingExportBatchWriter.ClearingExportLifecycleEventType> events = new ArrayList<>();
    writer.setLifecycleListener(event -> events.add(event.type()));
    ClearingExportWorkerConfig config = streamingConfig(1_000L);

    assertThatThrownBy(() -> writer.append("D|one", config))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("simulated open failure");

    assertThat(events).containsExactly(ClearingExportBatchWriter.ClearingExportLifecycleEventType.WRITE_FAILED);
  }

  @Test
  void clearsStreamingStateWhenReopenFailsAfterSuccessfulFinalize() throws Exception {
    ReopenFailingSink sink = new ReopenFailingSink();
    ClearingExportBatchWriter writer = newWriter(sink);
    List<ClearingExportBatchWriter.ClearingExportLifecycleEventType> events = new ArrayList<>();
    writer.setLifecycleListener(event -> events.add(event.type()));
    ClearingExportWorkerConfig config = streamingConfig(10L);

    writer.append("D|one", config);
    Thread.sleep(20L);
    assertThatThrownBy(() -> writer.append("D|two", config))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("simulated reopen failure");

    assertThat(writer.bufferedRecords()).isEqualTo(0L);
    assertThat(writer.filesWritten()).isEqualTo(1L);
    assertThat(events).containsExactly(
        ClearingExportBatchWriter.ClearingExportLifecycleEventType.CREATED,
        ClearingExportBatchWriter.ClearingExportLifecycleEventType.FLUSH_SUMMARY,
        ClearingExportBatchWriter.ClearingExportLifecycleEventType.WRITE_FAILED
    );
  }

  private static ClearingExportBatchWriter newWriter(ClearingExportSink sink) {
    ClearingExportFileAssembler assembler =
        new ClearingExportFileAssembler(new PebbleTemplateRenderer(), new XmlOutputFormatter());
    return new ClearingExportBatchWriter(assembler, sink);
  }

  private static ClearingExportWorkerConfig config(int maxRecordsPerFile, long flushIntervalMs) {
    return new ClearingExportWorkerConfig(
        "template",
        false,
        21_600_000L,
        maxRecordsPerFile,
        flushIntervalMs,
        100_000,
        true,
        "\n",
        "test.dat",
        "H|{{ now }}",
        "D|{{ record.payload }}",
        "T|{{ recordCount }}",
        "/tmp/clearing-hardening-tests",
        ".tmp",
        false,
        "reports/clearing/manifest.jsonl",
        "/tmp/schemas",
        null,
        null
    );
  }

  private static ClearingExportWorkerConfig streamingConfig(long streamingWindowMs) {
    return new ClearingExportWorkerConfig(
        "template",
        true,
        streamingWindowMs,
        1_000,
        1_000,
        100_000,
        true,
        "\n",
        "stream.dat",
        "H|{{ now }}",
        "D|{{ record.payload }}",
        "T|{{ recordCount }}",
        "/tmp/clearing-hardening-tests",
        ".tmp",
        false,
        "reports/clearing/manifest.jsonl",
        "/tmp/schemas",
        null,
        null
    );
  }

  private static final class RecordingSink implements ClearingExportSink {
    private final AtomicInteger writes = new AtomicInteger();
    private final int failWritesBeforeSuccess;

    private RecordingSink(int failWritesBeforeSuccess) {
      this.failWritesBeforeSuccess = failWritesBeforeSuccess;
    }

    @Override
    public ClearingExportSinkWriteResult writeFile(
        ClearingExportWorkerConfig config,
        ClearingRenderedFile file
    ) {
      int current = writes.incrementAndGet();
      if (current <= failWritesBeforeSuccess) {
        throw new IllegalStateException("simulated sink failure");
      }
      return new ClearingExportSinkWriteResult(
          file.fileName(),
          file.recordCount(),
          file.bytesUtf8(),
          Instant.parse("2026-02-22T00:00:00Z"),
          "memory://hardening/" + file.fileName());
    }
  }

  private static class StreamingFinalizeFailingSink implements ClearingExportSink {
    @Override
    public ClearingExportSinkWriteResult writeFile(
        ClearingExportWorkerConfig config,
        ClearingRenderedFile file
    ) {
      return new ClearingExportSinkWriteResult(
          file.fileName(),
          file.recordCount(),
          file.bytesUtf8(),
          Instant.parse("2026-02-22T00:00:00Z"),
          "memory://stream/" + file.fileName());
    }

    @Override
    public void openStreamingFile(
        ClearingExportWorkerConfig config,
        String fileName,
        String headerLine,
        String lineSeparator
    ) {
    }

    @Override
    public void appendStreamingRecord(
        ClearingExportWorkerConfig config,
        String fileName,
        String recordLine,
        String lineSeparator
    ) {
    }

    @Override
    public ClearingExportSinkWriteResult finalizeStreamingFile(
        ClearingExportWorkerConfig config,
        String fileName,
        String footerLine,
        String lineSeparator,
        int recordCount,
        Instant createdAt
    ) {
      throw new IllegalStateException("simulated finalize failure");
    }

    @Override
    public boolean supportsStreaming() {
      return true;
    }
  }

  private static final class StreamingOpenFailingSink extends StreamingFinalizeFailingSink {
    @Override
    public void openStreamingFile(
        ClearingExportWorkerConfig config,
        String fileName,
        String headerLine,
        String lineSeparator
    ) {
      throw new IllegalStateException("simulated open failure");
    }
  }

  private static final class ReopenFailingSink extends StreamingFinalizeFailingSink {
    private final AtomicInteger opens = new AtomicInteger();

    @Override
    public ClearingExportSinkWriteResult finalizeStreamingFile(
        ClearingExportWorkerConfig config,
        String fileName,
        String footerLine,
        String lineSeparator,
        int recordCount,
        Instant createdAt
    ) {
      return new ClearingExportSinkWriteResult(
          fileName,
          recordCount,
          0L,
          createdAt,
          "memory://stream/" + fileName);
    }

    @Override
    public void openStreamingFile(
        ClearingExportWorkerConfig config,
        String fileName,
        String headerLine,
        String lineSeparator
    ) {
      if (opens.incrementAndGet() > 1) {
        throw new IllegalStateException("simulated reopen failure");
      }
    }
  }
}
