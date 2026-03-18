package io.pockethive.postprocessor;

import jakarta.annotation.PreDestroy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.stereotype.Component;

@Component
class InfluxDb3TxOutcomeSink implements TxOutcomeSink {

  private static final DateTimeFormatter CLICKHOUSE_EVENT_TIME =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

  private final InfluxDb3SinkProperties properties;
  private final HttpClient client;
  private final ConcurrentLinkedQueue<String> buffer = new ConcurrentLinkedQueue<>();
  private final AtomicInteger bufferedCount = new AtomicInteger();
  private final AtomicLong lastFlushAtMs = new AtomicLong();
  private final ReentrantLock flushLock = new ReentrantLock();

  InfluxDb3TxOutcomeSink(InfluxDb3SinkProperties properties) {
    this.properties = Objects.requireNonNull(properties, "properties");
    this.client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofMillis(properties.getConnectTimeoutMs()))
        .build();
    this.lastFlushAtMs.set(System.currentTimeMillis());
  }

  @Override
  public TxOutcomeSinkMode mode() {
    return TxOutcomeSinkMode.INFLUXDB3;
  }

  @Override
  public void write(TxOutcomeEvent event) throws Exception {
    Objects.requireNonNull(event, "event");
    if (!properties.configured()) {
      throw new IllegalStateException("InfluxDB 3 sink is enabled but endpoint/database/measurement is not configured");
    }

    int maxBuffered = Math.max(1, properties.getMaxBufferedEvents());
    if (bufferedCount.get() >= maxBuffered) {
      throw new IllegalStateException("InfluxDB 3 tx-outcome buffer is full: maxBufferedEvents=" + maxBuffered);
    }

    buffer.add(toLineProtocol(event));
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
      URI uri = writeUri();
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
    HttpRequest.Builder request = HttpRequest.newBuilder(uri)
        .timeout(Duration.ofMillis(properties.getReadTimeoutMs()))
        .header("Content-Type", "text/plain; charset=utf-8")
        .POST(HttpRequest.BodyPublishers.ofString(String.join("\n", lines) + "\n", StandardCharsets.UTF_8));
    String token = trim(properties.getToken());
    if (!token.isEmpty()) {
      request.header("Authorization", "Bearer " + token);
    }
    HttpResponse<String> response = client.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    if (response.statusCode() != 204 && response.statusCode() / 100 != 2) {
      String body = response.body() == null ? "" : response.body().trim();
      if (body.length() > 500) {
        body = body.substring(0, 500) + "…";
      }
      throw new IllegalStateException("InfluxDB 3 write_lp failed status=" + response.statusCode() + " body=" + body);
    }
  }

  private URI writeUri() {
    String endpoint = trim(properties.getEndpoint());
    String base = endpoint.endsWith("/") ? endpoint.substring(0, endpoint.length() - 1) : endpoint;
    URI uri = URI.create(base + "/api/v3/write_lp");
    String separator = uri.toString().contains("?") ? "&" : "?";
    return URI.create(uri + separator + "db=" + encode(trim(properties.getDatabase())) + "&precision=ms");
  }

  private String toLineProtocol(TxOutcomeEvent event) {
    String measurement = escapeMeasurement(trim(properties.getMeasurement()));
    String callId = trim(event.callId());
    String businessCode = trim(event.businessCode());
    String traceId = trim(event.traceId());
    String dimensionsJson = escapeFieldString(json(event.dimensions()));

    StringBuilder line = new StringBuilder(512);
    line.append(measurement)
        .append(",swarmId=").append(escapeTag(event.swarmId()))
        .append(",sinkRole=").append(escapeTag(event.sinkRole()))
        .append(",sinkInstance=").append(escapeTag(event.sinkInstance()))
        .append(",callIdKey=").append(escapeTag(normalizeCallIdKey(callId)))
        .append(",businessCodeKey=").append(escapeTag(normalizeBusinessCodeKey(businessCode)))
        .append(",processorStatusClass=").append(escapeTag(processorStatusClass(event.processorStatus())))
        .append(" ")
        .append("processorStatus=").append(event.processorStatus()).append("i")
        .append(",processorSuccess=").append(event.processorSuccess()).append("i")
        .append(",processorDurationMs=").append(event.processorDurationMs()).append("i")
        .append(",businessSuccess=").append(event.businessSuccess()).append("i")
        .append(",traceId=\"").append(escapeFieldString(traceId)).append("\"")
        .append(",callId=\"").append(escapeFieldString(callId)).append("\"")
        .append(",businessCode=\"").append(escapeFieldString(businessCode)).append("\"")
        .append(",dimensionsJson=\"").append(dimensionsJson).append("\"")
        .append(" ")
        .append(toEpochMillis(event.eventTime()));
    return line.toString();
  }

  private static long toEpochMillis(String eventTime) {
    String value = trim(eventTime);
    try {
      return Instant.parse(value).toEpochMilli();
    } catch (Exception ignored) {
      return LocalDateTime.parse(value, CLICKHOUSE_EVENT_TIME).toInstant(ZoneOffset.UTC).toEpochMilli();
    }
  }

  private static String processorStatusClass(int status) {
    if (status >= 200 && status < 300) {
      return "2xx";
    }
    if (status >= 400 && status < 500) {
      return "4xx";
    }
    if (status >= 500 && status < 600) {
      return "5xx";
    }
    return "other";
  }

  private static String normalizeCallIdKey(String value) {
    return value.isEmpty() ? "unknown" : value;
  }

  private static String normalizeBusinessCodeKey(String value) {
    return value.isEmpty() ? "n/a" : value;
  }

  private static String json(Map<String, String> dimensions) {
    if (dimensions == null || dimensions.isEmpty()) {
      return "{}";
    }
    StringBuilder json = new StringBuilder("{");
    boolean first = true;
    for (Map.Entry<String, String> entry : dimensions.entrySet()) {
      if (!first) {
        json.append(",");
      }
      first = false;
      json.append("\"")
          .append(escapeFieldString(trim(entry.getKey())))
          .append("\":\"")
          .append(escapeFieldString(trim(entry.getValue())))
          .append("\"");
    }
    json.append("}");
    return json.toString();
  }

  private static String escapeMeasurement(String value) {
    return value.replace("\\", "\\\\").replace(",", "\\,").replace(" ", "\\ ");
  }

  private static String escapeTag(String value) {
    return value.replace("\\", "\\\\").replace(",", "\\,").replace(" ", "\\ ").replace("=", "\\=");
  }

  private static String escapeFieldString(String value) {
    return value.replace("\\", "\\\\").replace("\"", "\\\"");
  }

  private static String encode(String value) {
    return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  private static String trim(String value) {
    return value == null ? "" : value.trim();
  }
}
