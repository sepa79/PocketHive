package io.pockethive.processor.handler;

import io.pockethive.requestexecution.ApacheHttpRequestExecutor;
import io.pockethive.requestexecution.HttpExecutionRequest;
import io.pockethive.requestexecution.HttpExecutionResult;
import io.pockethive.requestexecution.HttpTargetResolver;
import io.pockethive.processor.ProcessorWorkerConfig;
import io.pockethive.processor.ResultRulesExtractor;
import io.pockethive.processor.metrics.*;
import io.pockethive.processor.exception.ProcessorCallException;
import io.pockethive.processor.response.ResponseBuilder;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.ObjectReader;
import io.pockethive.worker.sdk.api.HttpRequestEnvelope;
import io.pockethive.worker.sdk.api.HttpResultEnvelope;
import io.pockethive.worker.sdk.api.WorkItem;
import io.pockethive.worker.sdk.api.WorkerContext;
import java.net.URI;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.apache.hc.client5.http.classic.HttpClient;
import org.slf4j.Logger;

public class HttpProtocolHandler implements ProtocolHandler {
  private final ObjectMapper mapper;
  private final ObjectReader strictEnvelopeReader;
  private final Clock clock;
  private final CallMetricsRecorder metricsRecorder;
  private final HttpClient httpClient;
  private final HttpClient noKeepAliveClient;
  private final ThreadLocal<HttpClient> perThreadClient;
  private final HttpClient insecureHttpClient;
  private final HttpClient insecureNoKeepAliveClient;
  private final ThreadLocal<HttpClient> insecurePerThreadClient;
  private final java.util.concurrent.atomic.AtomicLong nextAllowedTimeNanos;

  public HttpProtocolHandler(ObjectMapper mapper, Clock clock, CallMetricsRecorder metricsRecorder,
                             HttpClient httpClient,
                             HttpClient noKeepAliveClient,
                             ThreadLocal<HttpClient> perThreadClient,
                             HttpClient insecureHttpClient,
                             HttpClient insecureNoKeepAliveClient,
                             ThreadLocal<HttpClient> insecurePerThreadClient,
                             java.util.concurrent.atomic.AtomicLong nextAllowedTimeNanos) {
    this.mapper = mapper;
    this.strictEnvelopeReader = mapper.readerFor(HttpRequestEnvelope.class)
        .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    this.clock = clock;
    this.metricsRecorder = metricsRecorder;
    this.httpClient = httpClient;
    this.noKeepAliveClient = noKeepAliveClient;
    this.perThreadClient = perThreadClient;
    this.insecureHttpClient = insecureHttpClient;
    this.insecureNoKeepAliveClient = insecureNoKeepAliveClient;
    this.insecurePerThreadClient = insecurePerThreadClient;
    this.nextAllowedTimeNanos = nextAllowedTimeNanos;
  }

  @Override
  public WorkItem invoke(WorkItem message, JsonNode envelope, ProcessorWorkerConfig config, WorkerContext context) throws Exception {
    Logger logger = context.logger();
    HttpRequestEnvelope requestEnvelope;
    try {
      requestEnvelope = parseEnvelope(envelope);
    } catch (IllegalArgumentException ex) {
      throw new ProcessorCallException(CallMetrics.failure(0L, 0L, -1), ex, Map.of("transport", "http"));
    }
    HttpRequestEnvelope.HttpRequest requestInfo = requestEnvelope.request();
    String method = requestInfo.method();
    String path = requestInfo.path();
    String baseUrl = config.baseUrl();
    Map<String, Object> unresolvedRequest = requestMetadata(null, method, baseUrl, path);
    boolean absolutePath = HttpTargetResolver.isAbsoluteHttpUri(path);
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
    Map<String, Object> requestMeta = requestMetadata(target, method, baseUrl, path);

    JsonNode headersNode = mapper.valueToTree(requestInfo.headers());
    headersNode.fields().forEachRemaining(entry -> logger.debug("header {}={}", entry.getKey(), entry.getValue().asText()));

    Optional<String> body = extractBody(requestInfo.body());
    logger.debug("HTTP REQUEST {} {} headers={} body={}", method, target, headersNode, body.orElse(""));

    long start = clock.millis();
    long pacingMillis = 0L;
    try {
      pacingMillis = applyExecutionMode(config);
      HttpClient client = selectClient(config);
      HttpExecutionResult outcome = new ApacheHttpRequestExecutor(client).execute(
          new HttpExecutionRequest(method, target, requestInfo.headers(), body.orElse(null)));
      long endMillis = clock.millis();
      long totalDuration = Math.max(0L, endMillis - start);
      long callDuration = Math.max(0L, totalDuration - pacingMillis);
      long connectionLatency = Math.max(0L, pacingMillis);
      logger.debug("HTTP RESPONSE {} {} -> {} latency={}ms body={}",
          method, target, outcome.statusCode(), callDuration, outcome.body());
      boolean success = outcome.statusCode() >= 200 && outcome.statusCode() < 300;
      CallMetrics metrics = success
          ? CallMetrics.success(callDuration, connectionLatency, outcome.statusCode())
          : CallMetrics.failure(callDuration, connectionLatency, outcome.statusCode());
      metricsRecorder.record(metrics);
      HttpResultEnvelope resultEnvelope = HttpResultEnvelope.of(
          mapper.convertValue(requestMeta, HttpResultEnvelope.HttpRequestInfo.class),
          new HttpResultEnvelope.HttpOutcome(
              HttpResultEnvelope.OUTCOME_HTTP_RESPONSE,
              outcome.statusCode(),
              outcome.headers(),
              outcome.body(),
              null
          ),
          new HttpResultEnvelope.HttpMetrics(metrics.durationMs(), metrics.connectionLatencyMs())
      );
      ObjectNode result = mapper.valueToTree(resultEnvelope);

      Map<String, Object> extractionHeaders = ResultRulesExtractor.extract(
          requestEnvelope.resultRules(),
          body.orElse(""),
          requestInfo.headers(),
          outcome.body(),
          outcome.headers()
      );

      WorkItem responseItem = ResponseBuilder.build(result, context.info(), metrics, extractionHeaders);
      WorkItem updated = message.addStep(context.info(), responseItem.asString(), responseItem.stepHeaders());
      return updated.toBuilder().contentType(responseItem.contentType()).build();
    } catch (Exception ex) {
      long now = clock.millis();
      long totalDuration = Math.max(0L, now - start);
      long callDuration = Math.max(0L, totalDuration - pacingMillis);
      long connectionLatency = Math.max(0L, pacingMillis);
      CallMetrics metrics = CallMetrics.failure(callDuration, connectionLatency, -1);
      metricsRecorder.record(metrics);
      throw new ProcessorCallException(metrics, ex, requestMeta);
    }
  }

  private HttpRequestEnvelope parseEnvelope(JsonNode envelope) {
    try {
      return strictEnvelopeReader.readValue(envelope);
    } catch (Exception ex) {
      // Intentionally fail-loud: malformed envelope/resultRules must not be silently ignored.
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
    try {
      return HttpTargetResolver.resolve(baseUrl, path);
    } catch (IllegalArgumentException ex) {
      return null;
    }
  }

  private HttpClient selectClient(ProcessorWorkerConfig config) {
    boolean sslVerify = Boolean.TRUE.equals(config.sslVerify());
    ProcessorWorkerConfig.ConnectionReuse reuse = config.connectionReuse();
    boolean keepAliveEnabled = Boolean.TRUE.equals(config.keepAlive());
    if (!keepAliveEnabled || reuse == ProcessorWorkerConfig.ConnectionReuse.NONE) {
      return sslVerify ? noKeepAliveClient : insecureNoKeepAliveClient;
    }
    if (reuse == ProcessorWorkerConfig.ConnectionReuse.PER_THREAD) {
      return sslVerify ? perThreadClient.get() : insecurePerThreadClient.get();
    }
    return sslVerify ? httpClient : insecureHttpClient;
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
