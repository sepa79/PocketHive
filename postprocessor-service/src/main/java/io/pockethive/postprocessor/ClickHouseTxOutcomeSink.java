package io.pockethive.postprocessor;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.sink.clickhouse.ClickHouseInsert;
import io.pockethive.sink.clickhouse.ClickHouseJsonEachRowTransport;
import io.pockethive.sink.clickhouse.ClickHouseSinkProperties;
import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.stereotype.Component;

/**
 * Responsibility: serialize and buffer transaction outcomes using its existing flush/failure policy.
 * Must not: construct ClickHouse HTTP requests, credentials or INSERT destinations.
 * Contract: RESP-CLICKHOUSE-INSERT — docs/architecture/runtime-responsibilities.md#resp-clickhouse-insert.
 */
@Component
class ClickHouseTxOutcomeSink implements TxOutcomeSink {

  private final ClickHouseSinkProperties properties;
  private final ObjectMapper objectMapper;
  private final ClickHouseJsonEachRowTransport transport;
  private final ConcurrentLinkedQueue<String> buffer = new ConcurrentLinkedQueue<>();
  private final AtomicInteger bufferedCount = new AtomicInteger();
  private final AtomicLong lastFlushAtMs = new AtomicLong();
  private final ReentrantLock flushLock = new ReentrantLock();

  ClickHouseTxOutcomeSink(ClickHouseSinkProperties properties, ObjectMapper objectMapper) {
    this.properties = Objects.requireNonNull(properties, "properties");
    this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    this.transport = new ClickHouseJsonEachRowTransport(properties);
  }

  @Override
  public TxOutcomeSinkMode mode() {
    return TxOutcomeSinkMode.CLICKHOUSE_V2;
  }

  @Override
  public void write(TxOutcomeEvent event) throws Exception {
    if (!properties.configured()) {
      throw new IllegalStateException("ClickHouse sink is enabled but endpoint/table is not configured");
    }

    int maxBuffered = Math.max(1, properties.getMaxBufferedEvents());
    if (bufferedCount.get() >= maxBuffered) {
      throw new ClickHouseTxOutcomeBufferFullException(
          "ClickHouse tx-outcome buffer is full: maxBufferedEvents=" + maxBuffered);
    }

    String line = objectMapper.writeValueAsString(event);
    buffer.add(line);
    int count = bufferedCount.incrementAndGet();

    long now = System.currentTimeMillis();
    int batchSize = Math.max(1, properties.getBatchSize());
    long flushIntervalMs = Math.max(1L, properties.getFlushIntervalMs());
    long last = lastFlushAtMs.get();

    if (count >= batchSize || now - last >= flushIntervalMs) {
      flush(now);
    }
  }

  void flush() throws Exception {
    flush(System.currentTimeMillis());
  }

  @PreDestroy
  void flushOnShutdown() {
    try {
      flush();
    } catch (Exception ignored) {
    }
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

      int batchSize = Math.max(1, properties.getBatchSize());
      int maxBatches = Math.max(1, (totalBuffered + batchSize - 1) / batchSize);
      ClickHouseInsert insert = transport.prepareInsert();
      for (int batch = 0; batch < maxBatches; batch++) {
        var lines = new ArrayList<String>(Math.min(batchSize, bufferedCount.get()));
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
          throw ex;
        }
      }
      lastFlushAtMs.set(nowMs);
    } finally {
      flushLock.unlock();
    }
  }
}
