package io.pockethive.clearingexport;

import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.stereotype.Component;

@Component
class ClearingExportBatchWriter {

  private final ClearingExportFileAssembler assembler;
  private final ClearingExportSink sink;
  private final ConcurrentLinkedQueue<String> bufferedLines = new ConcurrentLinkedQueue<>();
  private final ConcurrentLinkedQueue<StructuredProjectedRecord> bufferedStructured = new ConcurrentLinkedQueue<>();
  private final AtomicInteger bufferedCount = new AtomicInteger();
  private final AtomicLong lastFlushAtMs = new AtomicLong(System.currentTimeMillis());
  private final AtomicLong recordsAccepted = new AtomicLong();
  private final AtomicLong filesWritten = new AtomicLong();
  private final AtomicLong filesFailed = new AtomicLong();
  private final AtomicLong lastFileRecordCount = new AtomicLong();
  private final AtomicLong lastFileBytes = new AtomicLong();
  private final AtomicReference<String> lastFileName = new AtomicReference<>("");
  private final AtomicReference<String> lastError = new AtomicReference<>("");
  private final AtomicReference<Instant> lastFlushAt = new AtomicReference<>();
  private final ReentrantLock flushLock = new ReentrantLock();
  private final AtomicReference<ClearingExportWorkerConfig> lastConfig = new AtomicReference<>();
  private final AtomicReference<ClearingStructuredSchema> lastSchema = new AtomicReference<>();
  private final AtomicReference<StreamingTemplateFileState> streamingState = new AtomicReference<>();
  private final ScheduledExecutorService streamingTicker;

  ClearingExportBatchWriter(ClearingExportFileAssembler assembler, ClearingExportSink sink) {
    this.assembler = Objects.requireNonNull(assembler, "assembler");
    this.sink = Objects.requireNonNull(sink, "sink");
    this.streamingTicker = Executors.newSingleThreadScheduledExecutor(new StreamingTickerThreadFactory());
    this.streamingTicker.scheduleWithFixedDelay(this::tickStreamingFlush, 250, 250, TimeUnit.MILLISECONDS);
  }

  void append(String renderedRecordLine, ClearingExportWorkerConfig config) throws Exception {
    Objects.requireNonNull(renderedRecordLine, "renderedRecordLine");
    Objects.requireNonNull(config, "config");
    lastConfig.set(config);

    // NOTE: mode switch is evaluated from the current config.
    // Toggling streaming true->false at runtime is not supported; treat it as startup-time mode.
    if (config.streamingAppendEnabled()) {
      appendStreaming(renderedRecordLine, config, System.currentTimeMillis());
      return;
    }

    int maxBuffered = config.maxBufferedRecords();
    if (bufferedCount.get() >= maxBuffered) {
      throw new ClearingExportBufferFullException(
          "Clearing export buffer is full: maxBufferedRecords=" + maxBuffered);
    }

    bufferedLines.add(renderedRecordLine);
    bufferedCount.incrementAndGet();
    recordsAccepted.incrementAndGet();

    long now = System.currentTimeMillis();
    if (shouldFlush(config, now)) {
      flush(config, now);
    }
  }

  void appendStructured(
      StructuredProjectedRecord projectedRecord,
      ClearingExportWorkerConfig config,
      ClearingStructuredSchema schema
  ) throws Exception {
    Objects.requireNonNull(projectedRecord, "projectedRecord");
    Objects.requireNonNull(config, "config");
    Objects.requireNonNull(schema, "schema");
    lastConfig.set(config);
    lastSchema.set(schema);

    if (config.streamingAppendEnabled()) {
      throw new IllegalStateException("streamingAppendEnabled is supported only in template mode");
    }

    int maxBuffered = config.maxBufferedRecords();
    if (bufferedCount.get() >= maxBuffered) {
      throw new ClearingExportBufferFullException(
          "Clearing export buffer is full: maxBufferedRecords=" + maxBuffered);
    }
    bufferedStructured.add(projectedRecord);
    bufferedCount.incrementAndGet();
    recordsAccepted.incrementAndGet();

    long now = System.currentTimeMillis();
    if (shouldFlush(config, now)) {
      flush(config, now);
    }
  }

  void flushIfDue() throws Exception {
    ClearingExportWorkerConfig config = lastConfig.get();
    if (config == null) {
      return;
    }

    long now = System.currentTimeMillis();
    // NOTE: flush path is selected by current config value (see append() note about runtime mode toggles).
    if (config.streamingAppendEnabled()) {
      flushStreamingIfDue(config, now);
      return;
    }

    if (shouldFlush(config, now)) {
      flush(config, now);
    }
  }

  @PreDestroy
  void flushOnShutdown() {
    streamingTicker.shutdownNow();
    ClearingExportWorkerConfig config = lastConfig.get();
    if (config == null) {
      return;
    }
    try {
      // NOTE: shutdown finalization also follows current config value.
      // Streaming mode should be treated as startup-time mode (no live true->false toggle).
      if (config.streamingAppendEnabled()) {
        flushStreamingOnShutdown(config, System.currentTimeMillis());
      } else {
        flush(config, System.currentTimeMillis());
      }
    } catch (Exception ignored) {
      // Best-effort flush on shutdown.
    }
  }

  long bufferedRecords() {
    return bufferedCount.get();
  }

  long recordsAccepted() {
    return recordsAccepted.get();
  }

  long filesWritten() {
    return filesWritten.get();
  }

  long filesFailed() {
    return filesFailed.get();
  }

  long lastFileRecordCount() {
    return lastFileRecordCount.get();
  }

  long lastFileBytes() {
    return lastFileBytes.get();
  }

  String lastFileName() {
    return lastFileName.get();
  }

  String lastError() {
    return lastError.get();
  }

  Instant lastFlushAt() {
    return lastFlushAt.get();
  }

  void preflight(ClearingExportWorkerConfig config) {
    Objects.requireNonNull(config, "config");
    if (config.streamingAppendEnabled() && !sink.supportsStreaming()) {
      throw new IllegalStateException(
          "Configured sink does not support streaming append mode: " + sink.getClass().getSimpleName());
    }
  }

  private boolean shouldFlush(ClearingExportWorkerConfig config, long nowMs) {
    if (bufferedCount.get() <= 0) {
      return false;
    }
    if (bufferedCount.get() >= config.maxRecordsPerFile()) {
      return true;
    }
    return nowMs - lastFlushAtMs.get() >= config.flushIntervalMs();
  }

  private void flush(ClearingExportWorkerConfig config, long nowMs) throws Exception {
    if (!flushLock.tryLock()) {
      return;
    }
    try {
      int size = bufferedCount.get();
      if (size <= 0) {
        lastFlushAtMs.set(nowMs);
        return;
      }

      List<String> drainedTemplate = List.of();
      List<StructuredProjectedRecord> drainedStructured = List.of();
      try {
        ClearingRenderedFile file;
        int drainedCount;
        if (config.structuredMode()) {
          FlushStructuredPayload payload = drainStructured(size);
          drainedStructured = payload.projectedRecords();
          drainedCount = drainedStructured.size();
          if (drainedCount <= 0) {
            lastFlushAtMs.set(nowMs);
            return;
          }
          bufferedCount.addAndGet(-drainedCount);
          file = assembler.assembleStructured(
              config,
              requireSchema(),
              payload.recordValues(),
              payload.totals());
        } else {
          drainedTemplate = drainTemplate(size);
          drainedCount = drainedTemplate.size();
          if (drainedCount <= 0) {
            lastFlushAtMs.set(nowMs);
            return;
          }
          bufferedCount.addAndGet(-drainedCount);
          file = assembler.assemble(config, drainedTemplate);
        }
        ClearingExportSinkWriteResult sinkResult = sink.writeFile(config, file);
        filesWritten.incrementAndGet();
        lastFileName.set(sinkResult.fileName());
        lastFileRecordCount.set(sinkResult.recordCount());
        lastFileBytes.set(sinkResult.bytes());
        lastError.set("");
        lastFlushAt.set(sinkResult.createdAt());
      } catch (Exception ex) {
        if (config.structuredMode()) {
          for (StructuredProjectedRecord record : drainedStructured) {
            bufferedStructured.add(record);
          }
          bufferedCount.addAndGet(drainedStructured.size());
        } else {
          for (String line : drainedTemplate) {
            bufferedLines.add(line);
          }
          bufferedCount.addAndGet(drainedTemplate.size());
        }
        filesFailed.incrementAndGet();
        lastError.set(ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage());
        throw ex;
      } finally {
        lastFlushAtMs.set(nowMs);
      }
    } finally {
      flushLock.unlock();
    }
  }

  private void appendStreaming(String renderedRecordLine, ClearingExportWorkerConfig config, long nowMs)
      throws Exception {
    flushLock.lock();
    try {
      int maxBuffered = config.maxBufferedRecords();
      if (bufferedCount.get() >= maxBuffered) {
        throw new ClearingExportBufferFullException(
            "Clearing export buffer is full: maxBufferedRecords=" + maxBuffered);
      }

      StreamingTemplateFileState state = streamingState.get();
      if (state == null) {
        state = openStreamingTemplateFile(config, nowMs);
        streamingState.set(state);
      }

      if (state.recordCount > 0 && nowMs - state.openedAtMs >= config.streamingWindowMs()) {
        finalizeStreamingState(config, state, nowMs);
        state = openStreamingTemplateFile(config, nowMs);
        streamingState.set(state);
      }

      sink.appendStreamingRecord(config, state.fileName, renderedRecordLine, config.lineSeparator());
      state.recordCount++;
      bufferedCount.incrementAndGet();
      recordsAccepted.incrementAndGet();

      if (state.recordCount >= config.maxRecordsPerFile()) {
        finalizeStreamingState(config, state, nowMs);
        streamingState.set(null);
      }
    } catch (Exception ex) {
      filesFailed.incrementAndGet();
      lastError.set(ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage());
      throw ex;
    } finally {
      flushLock.unlock();
    }
  }

  private void flushStreamingIfDue(ClearingExportWorkerConfig config, long nowMs) throws Exception {
    if (!flushLock.tryLock()) {
      return;
    }
    try {
      StreamingTemplateFileState state = streamingState.get();
      if (state == null || state.recordCount <= 0) {
        return;
      }
      if (nowMs - state.openedAtMs >= config.streamingWindowMs()) {
        finalizeStreamingState(config, state, nowMs);
        streamingState.set(null);
      }
    } finally {
      flushLock.unlock();
    }
  }

  private void flushStreamingOnShutdown(ClearingExportWorkerConfig config, long nowMs) throws Exception {
    flushLock.lock();
    try {
      StreamingTemplateFileState state = streamingState.get();
      if (state == null || state.recordCount <= 0) {
        return;
      }
      finalizeStreamingState(config, state, nowMs);
      streamingState.set(null);
    } finally {
      flushLock.unlock();
    }
  }

  private void tickStreamingFlush() {
    try {
      ClearingExportWorkerConfig config = lastConfig.get();
      if (config == null || !config.streamingAppendEnabled()) {
        return;
      }
      flushStreamingIfDue(config, System.currentTimeMillis());
    } catch (Exception ignored) {
      // Best-effort periodic flush for streaming append mode.
    }
  }

  private StreamingTemplateFileState openStreamingTemplateFile(ClearingExportWorkerConfig config, long nowMs)
      throws Exception {
    Instant openedAt = Instant.ofEpochMilli(nowMs);
    String fileName = assembler.renderTemplateFileName(config, openedAt, 0);
    String header = assembler.renderTemplateHeader(config, openedAt, 0);
    sink.openStreamingFile(config, fileName, header, config.lineSeparator());
    return new StreamingTemplateFileState(fileName, nowMs);
  }

  private void finalizeStreamingState(
      ClearingExportWorkerConfig config,
      StreamingTemplateFileState state,
      long nowMs
  ) throws Exception {
    if (state.recordCount <= 0) {
      return;
    }

    Instant finalizeAt = Instant.ofEpochMilli(nowMs);
    String footer = assembler.renderTemplateFooter(config, finalizeAt, state.recordCount);

    ClearingExportSinkWriteResult sinkResult =
        sink.finalizeStreamingFile(
            config,
            state.fileName,
            footer,
            config.lineSeparator(),
            state.recordCount,
            finalizeAt);
    filesWritten.incrementAndGet();
    lastFileName.set(sinkResult.fileName());
    lastFileRecordCount.set(sinkResult.recordCount());
    lastFileBytes.set(sinkResult.bytes());
    lastError.set("");
    lastFlushAt.set(sinkResult.createdAt());
    bufferedCount.addAndGet(-state.recordCount);
    lastFlushAtMs.set(nowMs);
  }

  private List<String> drainTemplate(int max) {
    List<String> records = new ArrayList<>(max);
    for (int i = 0; i < max; i++) {
      String line = bufferedLines.poll();
      if (line == null) {
        break;
      }
      records.add(line);
    }
    return records;
  }

  private FlushStructuredPayload drainStructured(int max) {
    List<StructuredProjectedRecord> projected = new ArrayList<>(max);
    List<Map<String, String>> records = new ArrayList<>(max);
    Map<String, NumericAccumulator> aggregations = new LinkedHashMap<>();

    for (int i = 0; i < max; i++) {
      StructuredProjectedRecord record = bufferedStructured.poll();
      if (record == null) {
        break;
      }
      projected.add(record);
      records.add(record.values());
      for (Map.Entry<String, Double> numeric : record.numericValues().entrySet()) {
        NumericAccumulator accumulator = aggregations.computeIfAbsent(
            numeric.getKey(), ignored -> new NumericAccumulator());
        accumulator.accept(numeric.getValue());
      }
    }

    Map<String, Object> totals = new LinkedHashMap<>();
    totals.put("recordCount", records.size());
    for (Map.Entry<String, NumericAccumulator> entry : aggregations.entrySet()) {
      String suffix = toUpperCamel(entry.getKey());
      totals.put("sum" + suffix, entry.getValue().sum);
      totals.put("min" + suffix, entry.getValue().min);
      totals.put("max" + suffix, entry.getValue().max);
    }
    return new FlushStructuredPayload(projected, records, totals);
  }

  private ClearingStructuredSchema requireSchema() {
    ClearingStructuredSchema schema = lastSchema.get();
    if (schema == null) {
      throw new IllegalStateException("Structured schema is missing in batch writer");
    }
    return schema;
  }

  private static String toUpperCamel(String value) {
    if (value == null || value.isBlank()) {
      return "Value";
    }
    String[] parts = value.split("[._-]");
    StringBuilder out = new StringBuilder();
    for (String part : parts) {
      if (part.isBlank()) {
        continue;
      }
      out.append(Character.toUpperCase(part.charAt(0)));
      if (part.length() > 1) {
        out.append(part.substring(1));
      }
    }
    return out.isEmpty() ? "Value" : out.toString();
  }

  private record FlushStructuredPayload(
      List<StructuredProjectedRecord> projectedRecords,
      List<Map<String, String>> recordValues,
      Map<String, Object> totals
  ) {
  }

  private static final class StreamingTemplateFileState {
    private final String fileName;
    private final long openedAtMs;
    private int recordCount;

    private StreamingTemplateFileState(String fileName, long openedAtMs) {
      this.fileName = fileName;
      this.openedAtMs = openedAtMs;
      this.recordCount = 0;
    }
  }

  private static final class StreamingTickerThreadFactory implements ThreadFactory {
    @Override
    public Thread newThread(Runnable task) {
      Thread thread = new Thread(task, "clearing-export-streaming-ticker");
      thread.setDaemon(true);
      return thread;
    }
  }

  private static final class NumericAccumulator {
    private double sum;
    private Double min;
    private Double max;

    void accept(double value) {
      sum += value;
      min = min == null ? value : Math.min(min, value);
      max = max == null ? value : Math.max(max, value);
    }
  }
}
