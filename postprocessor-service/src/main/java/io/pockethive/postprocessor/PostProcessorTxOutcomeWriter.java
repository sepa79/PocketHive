package io.pockethive.postprocessor;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.sink.clickhouse.ClickHouseSinkProperties;
import jakarta.annotation.PreDestroy;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.stereotype.Component;

@Component
class PostProcessorTxOutcomeWriter {

  private final ClickHouseSinkProperties properties;
  private final ObjectMapper objectMapper;
  private final HttpClient client;
  private final ConcurrentLinkedQueue<String> buffer = new ConcurrentLinkedQueue<>();
  private final AtomicInteger bufferedCount = new AtomicInteger();
  private final AtomicLong lastFlushAtMs = new AtomicLong();
  private final ReentrantLock flushLock = new ReentrantLock();

  PostProcessorTxOutcomeWriter(ClickHouseSinkProperties properties, ObjectMapper objectMapper) {
    this.properties = Objects.requireNonNull(properties, "properties");
    this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    this.client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofMillis(properties.getConnectTimeoutMs()))
        .build();
  }

  void write(TxOutcomeEvent event) throws Exception {
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
      URI uri = insertUri();
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
          insertLines(uri, lines);
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

  private void insertLines(URI uri, java.util.List<String> lines) throws Exception {
    StringBuilder payload = new StringBuilder(lines.size() * 256);
    for (String line : lines) {
      payload.append(line).append('\n');
    }
    HttpRequest.Builder request = HttpRequest.newBuilder(uri)
        .timeout(Duration.ofMillis(properties.getReadTimeoutMs()))
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(payload.toString(), StandardCharsets.UTF_8));
    String username = trim(properties.getUsername());
    if (!username.isEmpty()) {
      String password = trim(properties.getPassword());
      String auth = username + ":" + password;
      String encoded = Base64.getEncoder().encodeToString(auth.getBytes(StandardCharsets.UTF_8));
      request.header("Authorization", "Basic " + encoded);
    }
    HttpResponse<String> response = client.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    if (response.statusCode() / 100 != 2) {
      String body = response.body() == null ? "" : response.body().trim();
      if (body.length() > 500) {
        body = body.substring(0, 500) + "…";
      }
      throw new IllegalStateException("ClickHouse insert failed status=" + response.statusCode() + " body=" + body);
    }
  }

  private URI insertUri() {
    String endpoint = trim(properties.getEndpoint());
    String base = endpoint.endsWith("/") ? endpoint.substring(0, endpoint.length() - 1) : endpoint;
    String query = "INSERT INTO " + trim(properties.getTable()) + " FORMAT JSONEachRow";
    String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8);
    return URI.create(base + "/?query=" + encoded);
  }

  private static String trim(String value) {
    return value == null ? "" : value.trim();
  }
}
