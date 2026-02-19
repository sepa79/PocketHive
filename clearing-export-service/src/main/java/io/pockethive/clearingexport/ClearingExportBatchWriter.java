package io.pockethive.clearingexport;

import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedQueue;
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

  ClearingExportBatchWriter(ClearingExportFileAssembler assembler, ClearingExportSink sink) {
    this.assembler = Objects.requireNonNull(assembler, "assembler");
    this.sink = Objects.requireNonNull(sink, "sink");
  }

  void append(String renderedRecordLine, ClearingExportWorkerConfig config) throws Exception {
    Objects.requireNonNull(renderedRecordLine, "renderedRecordLine");
    Objects.requireNonNull(config, "config");
    lastConfig.set(config);

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

  void flushIfDue() throws Exception {
    ClearingExportWorkerConfig config = lastConfig.get();
    if (config == null) {
      return;
    }
    long now = System.currentTimeMillis();
    if (shouldFlush(config, now)) {
      flush(config, now);
    }
  }

  @PreDestroy
  void flushOnShutdown() {
    ClearingExportWorkerConfig config = lastConfig.get();
    if (config == null) {
      return;
    }
    try {
      flush(config, System.currentTimeMillis());
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

      List<String> records = new ArrayList<>(size);
      for (int i = 0; i < size; i++) {
        String line = bufferedLines.poll();
        if (line == null) {
          break;
        }
        records.add(line);
      }

      if (records.isEmpty()) {
        lastFlushAtMs.set(nowMs);
        return;
      }

      bufferedCount.addAndGet(-records.size());
      try {
        ClearingRenderedFile file = assembler.assemble(config, records);
        sink.writeFile(config, file);
        filesWritten.incrementAndGet();
        lastFileName.set(file.fileName());
        lastFileRecordCount.set(file.recordCount());
        lastFileBytes.set(file.bytesUtf8());
        lastError.set("");
        lastFlushAt.set(file.createdAt());
      } catch (Exception ex) {
        for (String line : records) {
          bufferedLines.add(line);
        }
        bufferedCount.addAndGet(records.size());
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
}
