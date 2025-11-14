package io.pockethive.processor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.pockethive.worker.sdk.api.HttpWorkMessage;
import io.pockethive.worker.sdk.api.PocketHiveWorkerFunction;
import io.pockethive.worker.sdk.api.WorkMessage;
import io.pockethive.worker.sdk.api.WorkResult;
import io.pockethive.worker.sdk.api.WorkerContext;
import io.pockethive.worker.sdk.config.PocketHiveWorker;
import io.pockethive.worker.sdk.config.WorkerCapability;
import io.pockethive.worker.sdk.config.WorkerInputType;
import io.pockethive.worker.sdk.config.WorkerOutputType;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.DoubleAccumulator;
import java.util.concurrent.atomic.LongAdder;
import org.slf4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * PocketHive message worker that performs the "processor" hop inside the default swarm pipeline.
 * <p>
 * The worker is wired into the moderator queue configured via {@code pockethive.inputs.rabbit.queue}
 * (typically provided through {@code POCKETHIVE_INPUT_RABBIT_QUEUE}) and receives
 * {@link WorkMessage} payloads that typically originate from the orchestrator. For every incoming
 * message we resolve configuration from the {@link WorkerContext}:
 * <ul>
 *   <li>If control plane overrides exist they are surfaced through
 *       {@link WorkerContext#config(Class)}; otherwise we fall back to
 *       {@link ProcessorWorkerProperties#defaultConfig()} which points to {@code http://localhost:8082} and
 *       enables the worker by default.</li>
 *   <li>The resolved {@link ProcessorWorkerConfig#baseUrl() baseUrl} becomes the target for HTTP
 *       enrichment. You can override it through control-plane config payloads such as
 *       <pre>{@code {
 *   "baseUrl": "https://inventory.internal/api",
 *   "enabled": true
 * }}</pre></li>
 * </ul>
 * Once configured, the worker performs an outbound HTTP call using the {@link HttpWorkMessage}
 * envelope provided by upstream workers (method, url/baseUrl+path, query, headers, body). It falls
 * back to the legacy generator schema when the envelope is missing. Success and failure paths both emit a
 * {@link WorkResult} to the configured final routing key
 * ({@code pockethive.outputs.rabbit.routing-key}), and the runtime's observability interceptor adds the hop
 * metadata so downstream services can trace the request.
 * <p>
 * The defaults above can be tweaked by editing {@code processor-service/src/main/resources}
 * configuration or by publishing control-plane overrides on the {@code processor.control.*} routing
 * keys (for example {@code processor.control.config}).
 */
@Component("processorWorker")
@PocketHiveWorker(
    input = WorkerInputType.RABBITMQ,
    output = WorkerOutputType.RABBITMQ,
    capabilities = {WorkerCapability.MESSAGE_DRIVEN, WorkerCapability.HTTP},
    config = ProcessorWorkerConfig.class
)
class ProcessorWorkerImpl implements PocketHiveWorkerFunction {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final String HEADER_DURATION = "x-ph-processor-duration-ms";
  private static final String HEADER_SUCCESS = "x-ph-processor-success";
  private static final String HEADER_STATUS = "x-ph-processor-status";

  private final ProcessorWorkerProperties properties;
  private final HttpClient httpClient;
  private final Clock clock;
  private final LongAdder totalCalls = new LongAdder();
  private final LongAdder successfulCalls = new LongAdder();
  private final DoubleAccumulator totalLatencyMs = new DoubleAccumulator(Double::sum, 0.0);

  @Autowired
  ProcessorWorkerImpl(ProcessorWorkerProperties properties) {
    this(properties, HttpClient.newHttpClient(), Clock.systemUTC());
  }

  ProcessorWorkerImpl(ProcessorWorkerProperties properties, HttpClient httpClient, Clock clock) {
    this.properties = Objects.requireNonNull(properties, "properties");
    this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /**
   * Handles a single work message by resolving configuration, invoking the configured HTTP
   * endpoint, enriching the response, and acknowledging the result back to PocketHive.
   * <p>
   * Processing flow:
   * <ol>
   *   <li><strong>Configuration resolution</strong> – We first look for a runtime override via
   *       {@link WorkerContext#config(Class)}. When none is present the worker falls back to
   *       {@link ProcessorWorkerProperties#defaultConfig()} (enabled with {@code baseUrl=http://localhost:8082}).
   *       The active configuration is echoed to the control plane through
   *       {@link WorkerContext#statusPublisher()} for easy debugging.</li>
   *   <li><strong>HTTP invocation</strong> – Using {@link #invokeHttp(WorkMessage, ProcessorWorkerConfig, Logger)}
   *       we create a {@link HttpRequest} from the message body and forward it to the configured
   *       service. The request defaults to {@code GET /} with no body when fields are missing.</li>
   *   <li><strong>Error handling</strong> – Any exception (invalid config, network error, unexpected
   *       HTTP failure) is logged at {@code WARN} level and converted into an error message via
   *       {@link #buildError(WorkerContext, String, CallMetrics) buildError} so downstream services still receive a structured
   *       response.</li>
 *   <li><strong>Observability propagation</strong> – The runtime's
 *       {@code WorkerObservabilityInterceptor} attaches the hop metadata (role, instance id,
 *       timestamps) to the shared observability context so traces remain visible in Loki/Grafana.</li>
   * </ol>
   *
   * @param in incoming work message from the configured moderator queue
   * @param context context provided by the runtime (metrics, logging, config, observability)
   * @return a {@link WorkResult#message(WorkMessage)} destined for the configured final queue with the
   *         observability envelope already updated
   */
  @Override
  public WorkResult onMessage(WorkMessage in, WorkerContext context) {
    ProcessorWorkerConfig config = context.config(ProcessorWorkerConfig.class)
        .orElseGet(properties::defaultConfig);

    Logger logger = context.logger();
    try {
      WorkMessage response = invokeHttp(in, config, context);
      publishStatus(context, config);
      return WorkResult.message(response);
    } catch (ProcessorCallException ex) {
      logger.warn("Processor request failed: {}", ex.getCause() != null ? ex.getCause().toString() : ex.toString(), ex);
      WorkMessage error = buildError(context, ex.getCause() != null ? ex.getCause().toString() : ex.toString(), ex.metrics());
      publishStatus(context, config);
      return WorkResult.message(error);
    } catch (Exception ex) {
      logger.warn("Processor request failed: {}", ex.toString(), ex);
      WorkMessage error = buildError(context, ex.toString(), CallMetrics.failure(0, -1));
      publishStatus(context, config);
      return WorkResult.message(error);
    }
  }

  private WorkMessage invokeHttp(WorkMessage message, ProcessorWorkerConfig config, WorkerContext context)
      throws Exception {
    try {
      HttpWorkMessage envelope = message.asJson(HttpWorkMessage.class);
      return executeEnvelope(envelope, config, context);
    } catch (IllegalStateException ex) {
      // Fall back to legacy payloads that emit arbitrary JSON.
      return invokeLegacyPayload(message, config, context);
    }
  }

  private WorkMessage executeEnvelope(HttpWorkMessage envelope, ProcessorWorkerConfig config, WorkerContext context)
      throws Exception {
    Logger logger = context.logger();
    URI target = resolveTarget(envelope, config);
    if (target == null) {
      logger.warn("Unable to resolve target URL (envelopeUrl={}, baseUrl={}, path={})",
          envelope.url(), envelope.baseUrl(), envelope.path());
      return buildError(context, "invalidUrl", CallMetrics.failure(0, -1));
    }

    HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(target);
    envelope.headers().forEach(requestBuilder::header);
    Optional<String> body = Optional.ofNullable(envelope.body()).filter(b -> !b.isBlank());
    HttpRequest.BodyPublisher publisher = body
        .map(value -> HttpRequest.BodyPublishers.ofString(value, StandardCharsets.UTF_8))
        .orElse(HttpRequest.BodyPublishers.noBody());
    String method = envelope.method();
    requestBuilder.method(method, publisher);

    logger.debug("HTTP {} {} headers={} body={}", method, target, envelope.headers(), body.orElse(""));

    long start = clock.millis();
    try {
      HttpResponse<String> response = httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString());
      long duration = Math.max(0L, clock.millis() - start);
      logger.debug("HTTP {} {} -> {}", method, target, response.statusCode());

      boolean success = isSuccessful(response.statusCode());
      CallMetrics metrics = success
          ? CallMetrics.success(duration, response.statusCode())
          : CallMetrics.failure(duration, response.statusCode());
      recordCall(metrics);

      ObjectNode result = MAPPER.createObjectNode();
      result.put("status", response.statusCode());
      result.set("headers", MAPPER.valueToTree(response.headers().map()));
      result.put("body", response.body());

      return applyCallHeaders(WorkMessage.json(result)
          .header("content-type", "application/json")
          .header("x-ph-service", context.info().role()), metrics)
          .build();
    } catch (Exception ex) {
      long duration = Math.max(0L, clock.millis() - start);
      CallMetrics metrics = CallMetrics.failure(duration, -1);
      recordCall(metrics);
      throw new ProcessorCallException(metrics, ex);
    }
  }

  private WorkMessage invokeLegacyPayload(WorkMessage message, ProcessorWorkerConfig config, WorkerContext context)
      throws Exception {
    Logger logger = context.logger();
    JsonNode node = message.asJsonNode();
    String baseUrl = config.baseUrl();
    if (baseUrl == null || baseUrl.isBlank()) {
      logger.warn("No baseUrl configured; skipping HTTP call");
      return buildError(context, "invalid baseUrl", CallMetrics.failure(0, -1));
    }

    String path = node.path("path").asText("/");
    String method = node.path("method").asText("GET").toUpperCase();
    URI target = resolveTarget(baseUrl, path);
    if (target == null) {
      logger.warn("Invalid URI base='{}' path='{}'", baseUrl, path);
      return buildError(context, "invalid baseUrl", CallMetrics.failure(0, -1));
    }

    HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(target);
    JsonNode headersNode = node.path("headers");
    if (headersNode.isObject()) {
      headersNode.fields().forEachRemaining(entry -> requestBuilder.header(entry.getKey(), entry.getValue().asText()));
    }

    Optional<String> body = extractBody(node.path("body"));
    HttpRequest.BodyPublisher publisher = body
        .map(value -> HttpRequest.BodyPublishers.ofString(value, StandardCharsets.UTF_8))
        .orElse(HttpRequest.BodyPublishers.noBody());
    requestBuilder.method(method, publisher);

    logger.debug("HTTP REQUEST {} {} headers={} body={}", method, target, headersNode, body.orElse(""));

    long start = clock.millis();
    try {
      HttpResponse<String> response = httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString());
      long duration = Math.max(0L, clock.millis() - start);
      logger.debug("HTTP RESPONSE {} {} -> {}", method, target, response.statusCode());

      boolean success = isSuccessful(response.statusCode());
      CallMetrics metrics = success
          ? CallMetrics.success(duration, response.statusCode())
          : CallMetrics.failure(duration, response.statusCode());
      recordCall(metrics);

      ObjectNode result = MAPPER.createObjectNode();
      result.put("status", response.statusCode());
      result.set("headers", MAPPER.valueToTree(response.headers().map()));
      result.put("body", response.body());

      return applyCallHeaders(WorkMessage.json(result)
          .header("content-type", "application/json")
          .header("x-ph-service", context.info().role()), metrics)
          .build();
    } catch (Exception ex) {
      long duration = Math.max(0L, clock.millis() - start);
      CallMetrics metrics = CallMetrics.failure(duration, -1);
      recordCall(metrics);
      throw new ProcessorCallException(metrics, ex);
    }
  }

  private URI resolveTarget(HttpWorkMessage envelope, ProcessorWorkerConfig config) {
    String directUrl = normalize(envelope.url());
    Map<String, String> query = envelope.query();
    if (directUrl != null) {
      return buildUri(applyQuery(directUrl, query));
    }
    String base = firstNonBlank(envelope.baseUrl(), config.baseUrl());
    if (base == null || base.isBlank()) {
      return null;
    }
    String path = normalizePath(envelope.path());
    String combined = concatenate(base, path);
    return buildUri(applyQuery(combined, query));
  }

  private String applyQuery(String url, Map<String, String> query) {
    if (query == null || query.isEmpty()) {
      return url;
    }
    StringBuilder builder = new StringBuilder(url);
    builder.append(url.contains("?") ? "&" : "?");
    boolean first = true;
    for (Map.Entry<String, String> entry : query.entrySet()) {
      if (!first) {
        builder.append("&");
      }
      first = false;
      String key = encode(entry.getKey());
      String value = encode(entry.getValue());
      builder.append(key);
      builder.append("=");
      builder.append(value);
    }
    return builder.toString();
  }

  private URI buildUri(String target) {
    try {
      return URI.create(target);
    } catch (IllegalArgumentException ex) {
      return null;
    }
  }

  private static String encode(String value) {
    if (value == null) {
      return "";
    }
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  private static String concatenate(String base, String path) {
    if (path == null || path.isBlank()) {
      return base;
    }
    if (base.endsWith("/") && path.startsWith("/")) {
      return base + path.substring(1);
    }
    if (!base.endsWith("/") && !path.startsWith("/")) {
      return base + "/" + path;
    }
    return base + path;
  }

  private static String normalize(String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }

  private static String normalizePath(String path) {
    if (path == null || path.isBlank()) {
      return "";
    }
    return path.trim();
  }

  private static String firstNonBlank(String primary, String fallback) {
    String normalizedPrimary = normalize(primary);
    if (normalizedPrimary != null) {
      return normalizedPrimary;
    }
    return normalize(fallback);
  }

  private URI resolveTarget(String baseUrl, String path) {
    try {
      return URI.create(baseUrl).resolve(path == null ? "" : path);
    } catch (IllegalArgumentException ex) {
      return null;
    }
  }

  private Optional<String> extractBody(JsonNode bodyNode) throws Exception {
    if (bodyNode == null || bodyNode.isMissingNode() || bodyNode.isNull()) {
      return Optional.empty();
    }
    if (bodyNode.isTextual()) {
      return Optional.of(bodyNode.asText());
    }
    return Optional.of(MAPPER.writeValueAsString(bodyNode));
  }

  private WorkMessage buildError(WorkerContext context, String message, CallMetrics metrics) {
    ObjectNode result = MAPPER.createObjectNode();
    result.put("error", message);
    return applyCallHeaders(WorkMessage.json(result)
        .header("content-type", "application/json")
        .header("x-ph-service", context.info().role()), metrics)
        .build();
  }

  private WorkMessage.Builder applyCallHeaders(WorkMessage.Builder builder, CallMetrics metrics) {
    return builder
        .header(HEADER_DURATION, Long.toString(metrics.durationMs()))
        .header(HEADER_SUCCESS, Boolean.toString(metrics.success()))
        .header(HEADER_STATUS, Integer.toString(metrics.statusCode()));
  }

  private void publishStatus(WorkerContext context, ProcessorWorkerConfig config) {
    context.statusPublisher()
        .update(status -> status
            .data("baseUrl", config.baseUrl())
            .data("enabled", context.enabled())
            .data("transactions", totalCalls.sum())
            .data("successRatio", successRatio())
            .data("avgLatencyMs", averageLatencyMs()));
  }

  private void recordCall(CallMetrics metrics) {
    totalCalls.increment();
    totalLatencyMs.accumulate(metrics.durationMs());
    if (metrics.success()) {
      successfulCalls.increment();
    }
  }

  private double averageLatencyMs() {
    long calls = totalCalls.sum();
    if (calls == 0L) {
      return 0.0;
    }
    return totalLatencyMs.get() / calls;
  }

  private double successRatio() {
    long calls = totalCalls.sum();
    if (calls == 0L) {
      return 0.0;
    }
    return (double) successfulCalls.sum() / calls;
  }

  private boolean isSuccessful(int statusCode) {
    return statusCode >= 200 && statusCode < 300;
  }

  private static final class ProcessorCallException extends Exception {
    private final CallMetrics metrics;

    private ProcessorCallException(CallMetrics metrics, Exception cause) {
      super(cause);
      this.metrics = metrics;
    }

    private CallMetrics metrics() {
      return metrics;
    }
  }

  private record CallMetrics(long durationMs, boolean success, int statusCode) {
    private static CallMetrics success(long durationMs, int statusCode) {
      return new CallMetrics(durationMs, true, statusCode);
    }

    private static CallMetrics failure(long durationMs, int statusCode) {
      return new CallMetrics(durationMs, false, statusCode);
    }
  }

}
