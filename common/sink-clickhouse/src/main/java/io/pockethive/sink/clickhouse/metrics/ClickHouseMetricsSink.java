package io.pockethive.sink.clickhouse.metrics;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

public class ClickHouseMetricsSink implements ClickHouseMetricSampleSink {

  private static final DateTimeFormatter CLICKHOUSE_TIMESTAMP =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(ZoneOffset.UTC);
  private static final String INSERT_QUERY_TEMPLATE = "INSERT INTO %s FORMAT JSONEachRow";
  private static final int MAX_ERROR_BODY_LENGTH = 500;

  private final ClickHouseMetricsSinkProperties properties;
  private final ObjectMapper objectMapper;
  private final HttpClient client;
  private final ConcurrentLinkedQueue<String> buffer = new ConcurrentLinkedQueue<>();
  private final AtomicInteger bufferedCount = new AtomicInteger();
  private final AtomicLong lastFlushAtMs = new AtomicLong(System.currentTimeMillis());
  private final ReentrantLock flushLock = new ReentrantLock();

  public ClickHouseMetricsSink(ClickHouseMetricsSinkProperties properties, ObjectMapper objectMapper) {
    this(
        Objects.requireNonNull(properties, "properties"),
        objectMapper,
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(properties.getConnectTimeoutMs()))
            .build());
  }

  ClickHouseMetricsSink(
      ClickHouseMetricsSinkProperties properties,
      ObjectMapper objectMapper,
      HttpClient client) {
    this.properties = Objects.requireNonNull(properties, "properties");
    this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    this.client = Objects.requireNonNull(client, "client");
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
      URI uri = insertUri();
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

  private void insertLines(URI uri, List<String> lines) throws Exception {
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
      String auth = username + ":" + trim(properties.getPassword());
      String encoded = Base64.getEncoder().encodeToString(auth.getBytes(StandardCharsets.UTF_8));
      request.header("Authorization", "Basic " + encoded);
    }

    HttpResponse<String> response =
        client.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    if (response.statusCode() / 100 != 2) {
      String body = response.body() == null ? "" : response.body().trim();
      if (body.length() > MAX_ERROR_BODY_LENGTH) {
        body = body.substring(0, MAX_ERROR_BODY_LENGTH) + "...";
      }
      throw new IllegalStateException(
          "ClickHouse metrics insert failed status=" + response.statusCode() + " body=" + body);
    }
  }

  private URI insertUri() {
    String endpoint = trim(properties.getEndpoint());
    String base = endpoint.endsWith("/") ? endpoint.substring(0, endpoint.length() - 1) : endpoint;
    String query = INSERT_QUERY_TEMPLATE.formatted(properties.getTable());
    String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8);
    return URI.create(base + "/?query=" + encoded);
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

  private static String trim(String value) {
    return value == null ? "" : value.trim();
  }

  private record ClickHouseMetricRow(
      String eventTime,
      String swarmId,
      String runId,
      String role,
      String instance,
      String metricName,
      String metricKind,
      String statistic,
      double value,
      String unit,
      Map<String, String> labels) {
  }
}
