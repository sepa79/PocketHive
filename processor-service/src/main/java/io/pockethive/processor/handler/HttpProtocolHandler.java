package io.pockethive.processor.handler;

import io.pockethive.processor.ProcessorWorkerConfig;
import io.pockethive.processor.metrics.*;
import io.pockethive.processor.exception.ProcessorCallException;
import io.pockethive.processor.response.ResponseBuilder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.pockethive.worker.sdk.api.HttpRequestEnvelope;
import io.pockethive.worker.sdk.api.HttpResultEnvelope;
import io.pockethive.worker.sdk.api.WorkItem;
import io.pockethive.worker.sdk.api.WorkerContext;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.apache.hc.client5.http.classic.HttpClient;
import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.io.HttpClientResponseHandler;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.slf4j.Logger;

public class HttpProtocolHandler implements ProtocolHandler {
  private final ObjectMapper mapper;
  private final Clock clock;
  private final CallMetricsRecorder metricsRecorder;
  private final HttpClient httpClient;
  private final HttpClient noKeepAliveClient;
  private final ThreadLocal<HttpClient> perThreadClient;
  private final java.util.concurrent.atomic.AtomicLong nextAllowedTimeNanos;

  public HttpProtocolHandler(ObjectMapper mapper, Clock clock, CallMetricsRecorder metricsRecorder, HttpClient httpClient,
                             HttpClient noKeepAliveClient, ThreadLocal<HttpClient> perThreadClient,
                             java.util.concurrent.atomic.AtomicLong nextAllowedTimeNanos) {
    this.mapper = mapper;
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
    HttpRequestEnvelope.HttpRequest requestEnvelope = parseRequest(envelope);
    String method = requestEnvelope.method();
    String path = requestEnvelope.path();
    String baseUrl = config.baseUrl();
    Map<String, Object> unresolvedRequest = requestMetadata(null, method, baseUrl, path);
    boolean absolutePath = isAbsoluteHttpUri(path);
    if (!absolutePath && (baseUrl == null || baseUrl.isBlank())) {
      logger.warn("No baseUrl configured; skipping HTTP call");
      throw new ProcessorCallException(CallMetrics.failure(0L, 0L, -1),
          new IllegalArgumentException("invalid baseUrl"), unresolvedRequest);
    }

    URI target = resolveTarget(baseUrl, path);
    if (target == null) {
      logger.warn("Invalid URI base='{}' path='{}'", baseUrl, path);
      throw new ProcessorCallException(CallMetrics.failure(0L, 0L, -1),
          new IllegalArgumentException("invalid baseUrl"), unresolvedRequest);
    }
    Map<String, Object> request = requestMetadata(target, method, baseUrl, path);

    JsonNode headersNode = mapper.valueToTree(requestEnvelope.headers());
    headersNode.fields().forEachRemaining(entry -> logger.debug("header {}={}", entry.getKey(), entry.getValue().asText()));

    Optional<String> body = extractBody(requestEnvelope.body());
    logger.debug("HTTP REQUEST {} {} headers={} body={}", method, target, headersNode, body.orElse(""));

    long start = clock.millis();
    long pacingMillis = 0L;
    try {
      pacingMillis = applyExecutionMode(config);
      final long pacingMillisForHandler = pacingMillis;
      HttpClient client = selectClient(config);
      HttpUriRequestBase apacheRequest = new HttpUriRequestBase(method, target);
      headersNode.fields().forEachRemaining(entry -> apacheRequest.addHeader(entry.getKey(), entry.getValue().asText()));
      body.ifPresent(value -> apacheRequest.setEntity(new org.apache.hc.core5.http.io.entity.StringEntity(value, StandardCharsets.UTF_8)));

      record CallOutcome(int statusCode, Map<String, List<String>> headers, String body, CallMetrics metrics) {
      }

      HttpClientResponseHandler<CallOutcome> handler = response -> {
        long endMillis = clock.millis();
        long totalDuration = Math.max(0L, endMillis - start);
        long callDuration = Math.max(0L, totalDuration - pacingMillisForHandler);
        long connectionLatency = Math.max(0L, pacingMillisForHandler);
        int statusCode = response.getCode();

        String responseBody = response.getEntity() == null ? "" : EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8);
        logger.debug("HTTP RESPONSE {} {} -> {} latency={}ms body={}", method, target, statusCode, callDuration, responseBody);

        boolean success = statusCode >= 200 && statusCode < 300;
        CallMetrics metrics = success
            ? CallMetrics.success(callDuration, connectionLatency, statusCode)
            : CallMetrics.failure(callDuration, connectionLatency, statusCode);
        metricsRecorder.record(metrics);

        return new CallOutcome(statusCode, convertHeaders(response), responseBody, metrics);
      };

      CallOutcome outcome = client.execute(apacheRequest, handler);
      HttpResultEnvelope resultEnvelope = HttpResultEnvelope.of(
          mapper.convertValue(request, HttpResultEnvelope.HttpRequestInfo.class),
          new HttpResultEnvelope.HttpOutcome(
              HttpResultEnvelope.OUTCOME_HTTP_RESPONSE,
              outcome.statusCode(),
              outcome.headers(),
              outcome.body(),
              null
          ),
          new HttpResultEnvelope.HttpMetrics(outcome.metrics().durationMs(), outcome.metrics().connectionLatencyMs())
      );
      ObjectNode result = mapper.valueToTree(resultEnvelope);

      WorkItem responseItem = ResponseBuilder.build(result, context.info(), outcome.metrics());
      WorkItem updated = message.addStep(context.info(), responseItem.asString(), responseItem.stepHeaders());
      return updated.toBuilder().contentType(responseItem.contentType()).build();
    } catch (Exception ex) {
      long now = clock.millis();
      long totalDuration = Math.max(0L, now - start);
      long callDuration = Math.max(0L, totalDuration - pacingMillis);
      long connectionLatency = Math.max(0L, pacingMillis);
      CallMetrics metrics = CallMetrics.failure(callDuration, connectionLatency, -1);
      metricsRecorder.record(metrics);
      throw new ProcessorCallException(metrics, ex, request);
    }
  }

  private HttpRequestEnvelope.HttpRequest parseRequest(JsonNode envelope) {
    try {
      HttpRequestEnvelope parsed = mapper.treeToValue(envelope, HttpRequestEnvelope.class);
      return parsed.request();
    } catch (Exception ex) {
      throw new IllegalArgumentException("Invalid HTTP request envelope", ex);
    }
  }

  private Map<String, Object> requestMetadata(URI target, String method, String baseUrl, String path) {
    Map<String, Object> request = new LinkedHashMap<>();
    request.put("transport", "http");
    request.put("scheme", resolveScheme(target, baseUrl));
    request.put("method", method == null ? "" : method);
    request.put("baseUrl", baseUrl == null ? "" : baseUrl);
    request.put("path", path == null ? "" : path);
    if (target != null) {
      request.put("url", target.toString());
    }
    return request;
  }

  private String resolveScheme(URI target, String baseUrl) {
    if (target != null && target.getScheme() != null) {
      return target.getScheme().toLowerCase(Locale.ROOT);
    }
    try {
      URI base = URI.create(baseUrl);
      String scheme = base.getScheme();
      return scheme == null ? "" : scheme.toLowerCase(Locale.ROOT);
    } catch (Exception ex) {
      return "";
    }
  }

  private long applyExecutionMode(ProcessorWorkerConfig config) throws InterruptedException {
    ProcessorWorkerConfig.Mode mode = config.mode();
    if (mode == ProcessorWorkerConfig.Mode.RATE_PER_SEC) {
      double rate = config.ratePerSec();
      if (rate <= 0.0) return 0L;
      long intervalNanos = (long) (1_000_000_000L / rate);
      long now = System.nanoTime();
      long prev = nextAllowedTimeNanos.getAndUpdate(current -> {
        long base = Math.max(current, now);
        return base + intervalNanos;
      });
      long base = Math.max(prev, now);
      long scheduled = base + intervalNanos;
      long sleepNanos = scheduled - now;
      if (sleepNanos > 0L) {
        long millis = sleepNanos / 1_000_000L;
        int nanos = (int) (sleepNanos % 1_000_000L);
        Thread.sleep(millis, nanos);
        return sleepNanos / 1_000_000L;
      }
      return 0L;
    }
    return 0L;
  }

  private URI resolveTarget(String baseUrl, String path) {
    if (isAbsoluteHttpUri(path)) {
      try {
        return URI.create(path);
      } catch (IllegalArgumentException ex) {
        return null;
      }
    }
    try {
      return URI.create(baseUrl + (path == null ? "" : path));
    } catch (IllegalArgumentException ex) {
      return null;
    }
  }

  private boolean isAbsoluteHttpUri(String path) {
    if (path == null || path.isBlank()) {
      return false;
    }
    try {
      URI uri = URI.create(path);
      if (!uri.isAbsolute() || uri.getScheme() == null) {
        return false;
      }
      String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
      return "http".equals(scheme) || "https".equals(scheme);
    } catch (IllegalArgumentException ex) {
      return false;
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

  private Optional<String> extractBody(Object bodyValue) throws Exception {
    if (bodyValue == null) {
      return Optional.empty();
    }
    if (bodyValue instanceof String textValue) {
      return Optional.of(textValue);
    }
    return Optional.of(mapper.writeValueAsString(bodyValue));
  }
}
