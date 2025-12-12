package io.pockethive.processor.handler;

import io.pockethive.processor.ProcessorWorkerConfig;
import io.pockethive.processor.metrics.*;
import io.pockethive.processor.exception.ProcessorCallException;
import io.pockethive.processor.response.ResponseBuilder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.pockethive.worker.sdk.api.WorkItem;
import io.pockethive.worker.sdk.api.WorkerContext;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.apache.hc.client5.http.classic.HttpClient;
import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.slf4j.Logger;

public class HttpProtocolHandler implements ProtocolHandler {
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private final Clock clock;
  private final CallMetricsRecorder metricsRecorder;
  private final HttpClient httpClient;
  private final HttpClient noKeepAliveClient;
  private final ThreadLocal<HttpClient> perThreadClient;
  private final java.util.concurrent.atomic.AtomicLong nextAllowedTimeNanos;

  public HttpProtocolHandler(Clock clock, CallMetricsRecorder metricsRecorder, HttpClient httpClient,
                             HttpClient noKeepAliveClient, ThreadLocal<HttpClient> perThreadClient,
                             java.util.concurrent.atomic.AtomicLong nextAllowedTimeNanos) {
    this.clock = clock;
    this.metricsRecorder = metricsRecorder;
    this.httpClient = httpClient;
    this.noKeepAliveClient = noKeepAliveClient;
    this.perThreadClient = perThreadClient;
    this.nextAllowedTimeNanos = nextAllowedTimeNanos;
  }

  @Override
  public WorkItem invoke(WorkItem message, JsonNode envelope, ProcessorWorkerConfig config, WorkerContext context) throws Exception {
    Logger logger = context.logger();
    String baseUrl = config.baseUrl();
    if (baseUrl == null || baseUrl.isBlank()) {
      logger.warn("No baseUrl configured; skipping HTTP call");
      throw new ProcessorCallException(CallMetrics.failure(0L, 0L, -1), new IllegalArgumentException("invalid baseUrl"));
    }

    String path = envelope.path("path").asText("/");
    String method = envelope.path("method").asText("GET").toUpperCase();
    URI target = resolveTarget(baseUrl, path);
    if (target == null) {
      logger.warn("Invalid URI base='{}' path='{}'", baseUrl, path);
      throw new ProcessorCallException(CallMetrics.failure(0L, 0L, -1), new IllegalArgumentException("invalid baseUrl"));
    }

    JsonNode headersNode = envelope.path("headers");
    if (headersNode.isObject()) {
      headersNode.fields().forEachRemaining(entry -> logger.debug("header {}={}", entry.getKey(), entry.getValue().asText()));
    }

    Optional<String> body = extractBody(envelope.path("body"));
    logger.debug("HTTP REQUEST {} {} headers={} body={}", method, target, headersNode, body.orElse(""));

    long start = clock.millis();
    long pacingMillis = 0L;
    try {
      pacingMillis = applyExecutionMode(config);
      HttpClient client = selectClient(config);
      HttpUriRequestBase apacheRequest = new HttpUriRequestBase(method, target);
      headersNode.fields().forEachRemaining(entry -> apacheRequest.addHeader(entry.getKey(), entry.getValue().asText()));
      body.ifPresent(value -> apacheRequest.setEntity(new org.apache.hc.core5.http.io.entity.StringEntity(value, StandardCharsets.UTF_8)));

      ClassicHttpResponse response = (ClassicHttpResponse) client.execute(apacheRequest);
      long endMillis = clock.millis();
      long totalDuration = Math.max(0L, endMillis - start);
      long callDuration = Math.max(0L, totalDuration - pacingMillis);
      long connectionLatency = Math.max(0L, pacingMillis);
      int statusCode = response.getCode();
      logger.debug("HTTP RESPONSE {} {} -> {}", method, target, statusCode);

      boolean success = statusCode >= 200 && statusCode < 300;
      CallMetrics metrics = success
          ? CallMetrics.success(callDuration, connectionLatency, statusCode)
          : CallMetrics.failure(callDuration, connectionLatency, statusCode);
      metricsRecorder.record(metrics);

      ObjectNode result = MAPPER.createObjectNode();
      result.put("status", statusCode);
      result.set("headers", MAPPER.valueToTree(convertHeaders(response)));
      String responseBody = response.getEntity() == null ? "" : EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8);
      result.put("body", responseBody);

      WorkItem responseItem = ResponseBuilder.build(result, context.info().role(), metrics);
      return message.addStep(responseItem.asString(), responseItem.headers());
    } catch (Exception ex) {
      long now = clock.millis();
      long totalDuration = Math.max(0L, now - start);
      long callDuration = Math.max(0L, totalDuration - pacingMillis);
      long connectionLatency = Math.max(0L, pacingMillis);
      CallMetrics metrics = CallMetrics.failure(callDuration, connectionLatency, -1);
      metricsRecorder.record(metrics);
      throw new ProcessorCallException(metrics, ex);
    }
  }

  private long applyExecutionMode(ProcessorWorkerConfig config) throws InterruptedException {
    ProcessorWorkerConfig.Mode mode = config.mode();
    if (mode == ProcessorWorkerConfig.Mode.RATE_PER_SEC) {
      double rate = config.ratePerSec();
      if (rate <= 0.0) return 0L;
      long intervalNanos = (long) (1_000_000_000L / rate);
      long now = System.nanoTime();
      while (true) {
        long prev = nextAllowedTimeNanos.get();
        long base = Math.max(prev, now);
        long scheduled = base + intervalNanos;
        if (nextAllowedTimeNanos.compareAndSet(prev, scheduled)) {
          long sleepNanos = scheduled - now;
          if (sleepNanos > 0L) {
            long millis = sleepNanos / 1_000_000L;
            int nanos = (int) (sleepNanos % 1_000_000L);
            Thread.sleep(millis, nanos);
            return sleepNanos / 1_000_000L;
          }
          return 0L;
        }
      }
    }
    return 0L;
  }

  private URI resolveTarget(String baseUrl, String path) {
    try {
      return URI.create(baseUrl + (path == null ? "" : path));
    } catch (IllegalArgumentException ex) {
      return null;
    }
  }

  private HttpClient selectClient(ProcessorWorkerConfig config) {
    ProcessorWorkerConfig.ConnectionReuse reuse = config.connectionReuse();
    boolean keepAliveEnabled = Boolean.TRUE.equals(config.keepAlive());
    if (!keepAliveEnabled || reuse == ProcessorWorkerConfig.ConnectionReuse.NONE) {
      return noKeepAliveClient;
    }
    if (reuse == ProcessorWorkerConfig.ConnectionReuse.PER_THREAD) {
      return perThreadClient.get();
    }
    return httpClient;
  }

  private Map<String, List<String>> convertHeaders(ClassicHttpResponse response) {
    Header[] headers = response.getHeaders();
    if (headers == null || headers.length == 0) return Map.of();
    Map<String, List<String>> result = new java.util.LinkedHashMap<>();
    for (Header header : headers) {
      result.computeIfAbsent(header.getName(), k -> new ArrayList<>()).add(header.getValue());
    }
    return result;
  }

  private Optional<String> extractBody(JsonNode bodyNode) throws Exception {
    return bodyNode == null || bodyNode.isMissingNode() || bodyNode.isNull() ? Optional.empty()
        : bodyNode.isTextual() ? Optional.of(bodyNode.asText())
        : Optional.of(MAPPER.writeValueAsString(bodyNode));
  }
}
