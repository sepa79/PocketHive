package io.pockethive.processor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.pockethive.worker.sdk.api.PocketHiveWorkerFunction;
import io.pockethive.worker.sdk.api.WorkItem;
import io.pockethive.worker.sdk.api.WorkerContext;
import io.pockethive.worker.sdk.config.PocketHiveWorker;
import io.pockethive.worker.sdk.config.WorkerCapability;
import io.pockethive.worker.sdk.config.WorkerInputType;
import io.pockethive.worker.sdk.config.WorkerOutputType;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.DoubleAccumulator;
import java.util.concurrent.atomic.LongAdder;
import org.apache.hc.client5.http.classic.HttpClient;
import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.ConnectionReuseStrategy;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.slf4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * PocketHive message worker that performs the "processor" hop inside the default swarm pipeline.
 * <p>
 * The worker is wired into the moderator queue configured via {@code pockethive.inputs.rabbit.queue}
 * (typically provided through {@code POCKETHIVE_INPUT_RABBIT_QUEUE}) and receives
 * {@link WorkItem} payloads that typically originate from the orchestrator. For every incoming
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
 * Once configured, the worker performs an outbound HTTP call using the payload's {@code path},
 * {@code method}, {@code headers}, and {@code body} fields. Success and failure paths both emit a
 * {@link WorkItem} to the configured final routing key
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
    ioFromConfig = true,
    capabilities = {WorkerCapability.MESSAGE_DRIVEN, WorkerCapability.HTTP},
    config = ProcessorWorkerConfig.class
)
class ProcessorWorkerImpl implements PocketHiveWorkerFunction {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final String HEADER_DURATION = "x-ph-processor-duration-ms";
  private static final String HEADER_SUCCESS = "x-ph-processor-success";
  private static final String HEADER_STATUS = "x-ph-processor-status";
  private static final String HEADER_CONNECTION_LATENCY = "x-ph-processor-connection-latency-ms";
  private static final int GLOBAL_MAX_CONNECTIONS = 200;
  private static final int GLOBAL_MAX_PER_ROUTE = 200;

  private final ProcessorWorkerProperties properties;
  private final HttpClient httpClient;
  private final HttpClient noKeepAliveClient;
  private final ThreadLocal<HttpClient> perThreadClient;
  private final Clock clock;
  private final LongAdder totalCalls = new LongAdder();
  private final LongAdder successfulCalls = new LongAdder();
  private final DoubleAccumulator totalLatencyMs = new DoubleAccumulator(Double::sum, 0.0);
  private final java.util.concurrent.atomic.AtomicLong nextAllowedTimeNanos = new java.util.concurrent.atomic.AtomicLong(0L);

  @Autowired
  ProcessorWorkerImpl(ProcessorWorkerProperties properties) {
    this(
        properties,
        newPooledClient(),
        newNoKeepAliveClient(),
        Clock.systemUTC());
  }

  ProcessorWorkerImpl(ProcessorWorkerProperties properties,
                      HttpClient httpClient,
                      HttpClient noKeepAliveClient,
                      Clock clock) {
    this.properties = Objects.requireNonNull(properties, "properties");
    this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
    this.noKeepAliveClient = Objects.requireNonNull(noKeepAliveClient, "noKeepAliveClient");
    this.perThreadClient = ThreadLocal.withInitial(HttpClients::createDefault);
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
   *   <li><strong>HTTP invocation</strong> – Using {@link #invokeHttp(WorkItem, ProcessorWorkerConfig, Logger)}
   *       we create an HTTP request from the message body and forward it to the configured
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
   * @param in incoming work item from the configured moderator queue
   * @param context context provided by the runtime (metrics, logging, config, observability)
   * @return a {@link WorkItem} destined for the configured final queue with the
   *         observability envelope already updated
   */
  @Override
  public WorkItem onMessage(WorkItem in, WorkerContext context) {
    ProcessorWorkerConfig config = context.configOrDefault(ProcessorWorkerConfig.class, properties::defaultConfig);

    Logger logger = context.logger();
    try {
      WorkItem response = invokeHttp(in, config, context);
      publishStatus(context, config);
      return response;
    } catch (ProcessorCallException ex) {
      logger.warn("Processor request failed: {}", ex.getCause() != null ? ex.getCause().toString() : ex.toString(), ex);
      WorkItem error = buildError(in, context, ex.getCause() != null ? ex.getCause().toString() : ex.toString(), ex.metrics());
      publishStatus(context, config);
      return error;
    } catch (Exception ex) {
      logger.warn("Processor request failed: {}", ex.toString(), ex);
      WorkItem error = buildError(in, context, ex.toString(), CallMetrics.failure(0L, 0L, -1));
      publishStatus(context, config);
      return error;
    }
  }

  private WorkItem invokeHttp(WorkItem message, ProcessorWorkerConfig config, WorkerContext context)
      throws Exception {
    Logger logger = context.logger();
    JsonNode node = message.asJsonNode();
    String baseUrl = config.baseUrl();
    if (baseUrl == null || baseUrl.isBlank()) {
      logger.warn("No baseUrl configured; skipping HTTP call");
      return buildError(message, context, "invalid baseUrl", CallMetrics.failure(0L, 0L, -1));
    }

    String path = node.path("path").asText("/");
    String method = node.path("method").asText("GET").toUpperCase();
    URI target = resolveTarget(baseUrl, path);
    if (target == null) {
      logger.warn("Invalid URI base='{}' path='{}'", baseUrl, path);
      return buildError(message, context, "invalid baseUrl", CallMetrics.failure(0L, 0L, -1));
    }

	    JsonNode headersNode = node.path("headers");
	    if (headersNode.isObject()) {
	      headersNode.fields().forEachRemaining(entry -> logger.debug("header {}={}", entry.getKey(), entry.getValue().asText()));
	    }

	    Optional<String> body = extractBody(node.path("body"));
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

      boolean success = isSuccessful(statusCode);
      CallMetrics metrics = success
          ? CallMetrics.success(callDuration, connectionLatency, statusCode)
          : CallMetrics.failure(callDuration, connectionLatency, statusCode);
      recordCall(metrics);

      ObjectNode result = MAPPER.createObjectNode();
      result.put("status", statusCode);
      result.set("headers", MAPPER.valueToTree(convertHeaders(response)));
      String responseBody = response.getEntity() == null
          ? ""
          : EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8);
      result.put("body", responseBody);

      WorkItem responseItem = applyCallHeaders(WorkItem.json(result)
          .header("content-type", "application/json")
          .header("x-ph-service", context.info().role()), metrics)
          .build();

      return message.addStep(responseItem.asString(), responseItem.headers());
    } catch (Exception ex) {
      long now = clock.millis();
      long totalDuration = Math.max(0L, now - start);
      long callDuration = Math.max(0L, totalDuration - pacingMillis);
      long connectionLatency = Math.max(0L, pacingMillis);
      CallMetrics metrics = CallMetrics.failure(callDuration, connectionLatency, -1);
      recordCall(metrics);
      throw new ProcessorCallException(metrics, ex);
    }
  }

  /**
   * Applies the configured execution mode for this call.
   *
   * @return pacing delay in milliseconds applied before the HTTP call.
   */
  private long applyExecutionMode(ProcessorWorkerConfig config) throws InterruptedException {
    ProcessorWorkerConfig.Mode mode = config.mode();
    if (mode == ProcessorWorkerConfig.Mode.RATE_PER_SEC) {
      double rate = config.ratePerSec();
      if (rate <= 0.0) {
        return 0L;
      }
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
      String suffix = path == null ? "" : path;
      return URI.create(baseUrl + suffix);
    } catch (IllegalArgumentException ex) {
      return null;
    }
  }

  /**
   * Selects the HttpClient to use for the current call based on the configured connection reuse
   * mode.
   */
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
    if (headers == null || headers.length == 0) {
      return Map.of();
    }
    Map<String, List<String>> result = new java.util.LinkedHashMap<>();
    for (Header header : headers) {
      String name = header.getName();
      String value = header.getValue();
      List<String> values = result.computeIfAbsent(name, k -> new ArrayList<>());
      values.add(value);
    }
    return result;
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

  private WorkItem buildError(WorkItem in, WorkerContext context, String message, CallMetrics metrics) {
    ObjectNode result = MAPPER.createObjectNode();
    result.put("error", message);
    WorkItem errorItem = applyCallHeaders(WorkItem.json(result)
        .header("content-type", "application/json")
        .header("x-ph-service", context.info().role()), metrics)
        .build();
    return in.addStep(errorItem.asString(), errorItem.headers());
  }

  private WorkItem.Builder applyCallHeaders(WorkItem.Builder builder, CallMetrics metrics) {
    return builder
        .header(HEADER_DURATION, Long.toString(metrics.durationMs()))
        .header(HEADER_CONNECTION_LATENCY, Long.toString(metrics.connectionLatencyMs()))
        .header(HEADER_SUCCESS, Boolean.toString(metrics.success()))
        .header(HEADER_STATUS, Integer.toString(metrics.statusCode()));
  }

  private void publishStatus(WorkerContext context, ProcessorWorkerConfig config) {
    int httpMaxConnections = httpMaxConnections(config);
    context.statusPublisher()
        .update(status -> status
            .data("baseUrl", config.baseUrl())
            .data("enabled", context.enabled())
            .data("httpMode", config.mode().name())
            .data("httpThreadCount", config.threadCount())
            .data("httpMaxConnections", httpMaxConnections)
            .data("transactions", totalCalls.sum())
            .data("successRatio", successRatio())
            .data("avgLatencyMs", averageLatencyMs()));
  }

  private int httpMaxConnections(ProcessorWorkerConfig config) {
    boolean keepAliveEnabled = Boolean.TRUE.equals(config.keepAlive());
    if (!keepAliveEnabled) {
      return 0;
    }
    ProcessorWorkerConfig.ConnectionReuse reuse = config.connectionReuse();
    if (reuse == ProcessorWorkerConfig.ConnectionReuse.GLOBAL) {
      return GLOBAL_MAX_CONNECTIONS;
    }
    if (reuse == ProcessorWorkerConfig.ConnectionReuse.PER_THREAD) {
      return config.threadCount();
    }
    return 0;
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

  private static HttpClient newPooledClient() {
    PoolingHttpClientConnectionManager manager = new PoolingHttpClientConnectionManager();
    manager.setMaxTotal(GLOBAL_MAX_CONNECTIONS);
    manager.setDefaultMaxPerRoute(GLOBAL_MAX_PER_ROUTE);
    return HttpClients.custom()
        .setConnectionManager(manager)
        .build();
  }

  private static HttpClient newNoKeepAliveClient() {
    ConnectionReuseStrategy noReuse = (request, response, context) -> false;
    return HttpClients.custom()
        .setConnectionReuseStrategy(noReuse)
        .build();
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

  private record CallMetrics(long durationMs, long connectionLatencyMs, boolean success, int statusCode) {
    private static CallMetrics success(long durationMs, long connectionLatencyMs, int statusCode) {
      return new CallMetrics(durationMs, connectionLatencyMs, true, statusCode);
    }

    private static CallMetrics failure(long durationMs, long connectionLatencyMs, int statusCode) {
      return new CallMetrics(durationMs, connectionLatencyMs, false, statusCode);
    }
  }

}
