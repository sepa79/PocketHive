package io.pockethive.clearingexport;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ClearingExportBatchWriterTest {

  @Test
  void firstTemplateRecordAfterLongIdleStartsNewFlushWindow() throws Exception {
    AtomicLong nowMs = new AtomicLong(0L);
    ClearingExportFileAssembler assembler = mock(ClearingExportFileAssembler.class);
    ClearingExportSink sink = mock(ClearingExportSink.class);
    ClearingExportBatchWriter writer = new ClearingExportBatchWriter(assembler, sink, nowMs::get, false);
    ClearingExportWorkerConfig config = templateConfig();

    when(assembler.assemble(eq(config), anyList()))
        .thenAnswer(invocation -> new ClearingRenderedFile(
            "template.txt",
            "payload",
            ((List<?>) invocation.getArgument(1)).size(),
            Instant.EPOCH));
    when(sink.writeFile(eq(config), any(ClearingRenderedFile.class)))
        .thenAnswer(invocation -> sinkResult(invocation.getArgument(1)));

    nowMs.set(120_000L);
    writer.append("record-1", config);

    assertThat(writer.bufferedRecords()).isEqualTo(1);
    assertThat(writer.filesWritten()).isZero();
    verifyNoInteractions(assembler, sink);

    nowMs.set(180_001L);
    writer.flushIfDue();

    assertThat(writer.bufferedRecords()).isZero();
    assertThat(writer.filesWritten()).isEqualTo(1);
    verify(assembler).assemble(eq(config), anyList());
    verify(sink).writeFile(eq(config), any(ClearingRenderedFile.class));
  }

  @Test
  void firstStructuredRecordAfterLongIdleStartsNewFlushWindow() throws Exception {
    AtomicLong nowMs = new AtomicLong(0L);
    ClearingExportFileAssembler assembler = mock(ClearingExportFileAssembler.class);
    ClearingExportSink sink = mock(ClearingExportSink.class);
    ClearingExportBatchWriter writer = new ClearingExportBatchWriter(assembler, sink, nowMs::get, false);
    ClearingExportWorkerConfig config = structuredConfig();
    ClearingStructuredSchema schema = structuredSchema();

    when(assembler.assembleStructured(eq(config), eq(schema), anyList(), anyMap()))
        .thenAnswer(invocation -> new ClearingRenderedFile(
            "structured.xml",
            "<xml/>",
            ((List<?>) invocation.getArgument(2)).size(),
            Instant.EPOCH));
    when(sink.writeFile(eq(config), any(ClearingRenderedFile.class)))
        .thenAnswer(invocation -> sinkResult(invocation.getArgument(1)));

    nowMs.set(120_000L);
    writer.appendStructured(
        new StructuredProjectedRecord(Map.of("payload", "value"), Map.of("unitAmount", 1.0)),
        config,
        schema);

    assertThat(writer.bufferedRecords()).isEqualTo(1);
    assertThat(writer.filesWritten()).isZero();
    verifyNoInteractions(assembler, sink);

    nowMs.set(180_001L);
    writer.flushIfDue();

    assertThat(writer.bufferedRecords()).isZero();
    assertThat(writer.filesWritten()).isEqualTo(1);
    verify(assembler).assembleStructured(eq(config), eq(schema), anyList(), anyMap());
    verify(sink).writeFile(eq(config), any(ClearingRenderedFile.class));
  }

  private static ClearingExportWorkerConfig templateConfig() {
    return new ClearingExportWorkerConfig(
        "template",
        false,
        60_000L,
        10,
        60_000L,
        5_000,
        true,
        "\n",
        "out.txt",
        "H",
        "D",
        "T",
        "/tmp/out",
        ".tmp",
        false,
        "manifest.jsonl",
        "/app/scenario/clearing-schemas",
        "",
        "");
  }

  private static ClearingExportWorkerConfig structuredConfig() {
    return new ClearingExportWorkerConfig(
        "structured",
        false,
        60_000L,
        10,
        60_000L,
        5_000,
        true,
        "\n",
        "unused.txt",
        "H",
        "D",
        "T",
        "/tmp/out",
        ".tmp",
        false,
        "manifest.jsonl",
        "/app/scenario/clearing-schemas",
        "test-schema",
        "1.0.0");
  }

  private static ClearingStructuredSchema structuredSchema() {
    return new ClearingStructuredSchema(
        "test-schema",
        "1.0.0",
        "xml",
        "out.xml",
        Map.of("payload", new ClearingStructuredSchema.StructuredFieldRule("{{ steps.selected.payload }}", true, "string")),
        Map.of(),
        Map.of("recordCount", "{{ recordCount }}"),
        new ClearingStructuredSchema.XmlOutputConfig(
            true,
            "UTF-8",
            "Document",
            "Header",
            "Transactions",
            "Transaction",
            "Footer",
            "",
            "",
            "",
            "",
            false));
  }

  private static ClearingExportSinkWriteResult sinkResult(ClearingRenderedFile file) {
    return new ClearingExportSinkWriteResult(
        file.fileName(),
        file.recordCount(),
        file.bytesUtf8(),
        Instant.EPOCH,
        "/tmp/" + file.fileName());
  }
}
