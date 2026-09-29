package io.pockethive.sink.clickhouse.metrics;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.sink.clickhouse.ClickHouseInsert;
import io.pockethive.sink.clickhouse.ClickHouseJsonEachRowTransport;
import io.pockethive.sink.clickhouse.ClickHouseInsertException;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Responsibility: serialize and buffer metric samples using its existing flush/failure policy.
 * Must not: construct ClickHouse HTTP requests, credentials or INSERT destinations.
 * Contract: RESP-CLICKHOUSE-INSERT — docs/architecture/runtime-responsibilities.md#resp-clickhouse-insert.
 */
public class ClickHouseMetricsSink implements ClickHouseMetricSampleSink {

  private static final DateTimeFormatter CLICKHOUSE_TIMESTAMP =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(ZoneOffset.UTC);

  private final ClickHouseMetricsSinkProperties properties;
  private final ObjectMapper objectMapper;
  private final ClickHouseJsonEachRowTransport transport;
  private final ConcurrentLinkedQueue<String> buffer = new ConcurrentLinkedQueue<>();
  private final AtomicInteger bufferedCount = new AtomicInteger();
  private final AtomicLong lastFlushAtMs = new AtomicLong(System.currentTimeMillis());
  private final ReentrantLock flushLock = new ReentrantLock();

  public ClickHouseMetricsSink(ClickHouseMetricsSinkProperties properties, ObjectMapper objectMapper) {
    this.properties = Objects.requireNonNull(properties, "properties");
    this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    this.transport = new ClickHouseJsonEachRowTransport(properties);
  }

  public void write(ClickHouseMetricSample sample) throws Exception {
    Objects.requireNonNull(sample, "sample");
    ensureConfigured();
    validateLabelBounds(sample.labels());

    int maxBuffered = properties.getMaxBufferedSamples();
    if (bufferedCount.get() >= maxBuffered) {
      throw new ClickHouseMetricsBufferFullException(
          "ClickHouse metrics buffer is full: maxBufferedSamples=" + maxBuffered);
    }

    buffer.add(objectMapper.writeValueAsString(row(sample)));
    int count = bufferedCount.incrementAndGet();

    long now = System.currentTimeMillis();
    int batchSize = properties.getBatchSize();
    long flushIntervalMs = properties.getFlushIntervalMs();
    long last = lastFlushAtMs.get();
    if (count >= batchSize || now - last >= flushIntervalMs) {
      flush(now);
    }
  }

  public void flush() throws Exception {
    flush(System.currentTimeMillis());
  }

  @Override
  public void close() throws Exception {
    flush();
  }

  int bufferedSamples() {
    return bufferedCount.get();
  }

  private void flush(long nowMs) throws Exception {
    if (!flushLock.tryLock()) {
      return;
    }
    try {
      int totalBuffered = bufferedCount.get();
      if (totalBuffered <= 0) {
        lastFlushAtMs.set(nowMs);
        return;
      }

      int batchSize = properties.getBatchSize();
      int maxBatches = Math.max(1, (totalBuffered + batchSize - 1) / batchSize);
      ClickHouseInsert insert = transport.prepareInsert();
      for (int batch = 0; batch < maxBatches; batch++) {
        List<String> lines = new ArrayList<>(Math.min(batchSize, bufferedCount.get()));
        for (int i = 0; i < batchSize; i++) {
          String line = buffer.poll();
          if (line == null) {
            break;
          }
          lines.add(line);
        }
        if (lines.isEmpty()) {
          break;
        }
        bufferedCount.addAndGet(-lines.size());
        try {
          insert.write(lines);
        } catch (Exception ex) {
          for (String line : lines) {
            buffer.add(line);
          }
          bufferedCount.addAndGet(lines.size());
          if (ex instanceof ClickHouseInsertException failure) {
            throw new IllegalStateException(failure.describe("ClickHouse metrics insert", "..."));
          }
          throw ex;
        }
      }
      lastFlushAtMs.set(nowMs);
    } finally {
      flushLock.unlock();
    }
  }

  private void ensureConfigured() {
    properties.requireConfigured();
  }

  private void validateLabelBounds(Map<String, String> labels) {
    int maxCount = properties.getMaxLabelCount();
    int maxKeyLength = properties.getMaxLabelKeyLength();
    int maxValueLength = properties.getMaxLabelValueLength();
    if (labels.size() > maxCount) {
      throw new ClickHouseMetricSampleRejectedException(
          "ClickHouse metrics label count exceeds maxLabelCount=" + maxCount + ": " + labels.size());
    }
    labels.forEach((key, value) -> {
      if (key.length() > maxKeyLength) {
        throw new ClickHouseMetricSampleRejectedException(
            "ClickHouse metrics label key exceeds maxLabelKeyLength=" + maxKeyLength + ": " + key);
      }
      if (value.length() > maxValueLength) {
        throw new ClickHouseMetricSampleRejectedException(
            "ClickHouse metrics label value exceeds maxLabelValueLength=" + maxValueLength + " for key=" + key);
      }
    });
  }

  private static ClickHouseMetricRow row(ClickHouseMetricSample sample) {
    return new ClickHouseMetricRow(
        CLICKHOUSE_TIMESTAMP.format(sample.eventTime()),
        sample.swarmId(),
        sample.runId(),
        sample.role(),
        sample.instance(),
        sample.metricName(),
        sample.metricKind().name(),
        sample.statistic().name(),
        sample.value(),
        sample.unit(),
        sample.labels());
  }
}
