package io.pockethive.processor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.pockethive.processor.handler.ProtocolHandler;
import io.pockethive.processor.handler.HttpProtocolHandler;
import io.pockethive.processor.handler.TcpProtocolHandler;
import io.pockethive.processor.metrics.CallMetrics;
import io.pockethive.processor.metrics.CallMetricsRecorder;
import io.pockethive.processor.exception.ProcessorCallException;
import io.pockethive.processor.response.ResponseBuilder;
import io.pockethive.worker.sdk.api.PocketHiveWorkerFunction;
import io.pockethive.worker.sdk.api.WorkItem;
import io.pockethive.worker.sdk.api.WorkerContext;
import io.pockethive.worker.sdk.config.PocketHiveWorker;
import io.pockethive.worker.sdk.config.WorkerCapability;

import java.time.Clock;
import java.util.Map;
import java.util.Objects;
import org.apache.hc.client5.http.classic.HttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.core5.http.ConnectionReuseStrategy;
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
    capabilities = {WorkerCapability.MESSAGE_DRIVEN, WorkerCapability.HTTP},
    config = ProcessorWorkerConfig.class
)
class ProcessorWorkerImpl implements PocketHiveWorkerFunction {

  private static final int GLOBAL_MAX_CONNECTIONS = 200;
  private static final int GLOBAL_MAX_PER_ROUTE = 200;

  private final ObjectMapper mapper;
  private final ProcessorWorkerProperties properties;
  private final CallMetricsRecorder metricsRecorder = new CallMetricsRecorder();
  private final Map<String, ProtocolHandler> protocolHandlers;

  @Autowired
  ProcessorWorkerImpl(ObjectMapper mapper, ProcessorWorkerProperties properties) {
    this(mapper, properties, newPooledClient(), newNoKeepAliveClient(), Clock.systemUTC());
  }

  ProcessorWorkerImpl(ObjectMapper mapper, ProcessorWorkerProperties properties, HttpClient httpClient, HttpClient noKeepAliveClient, Clock clock) {
    this.mapper = Objects.requireNonNull(mapper, "mapper");
    this.properties = Objects.requireNonNull(properties, "properties");
    ThreadLocal<HttpClient> perThreadClient = ThreadLocal.withInitial(HttpClients::createDefault);
    java.util.concurrent.atomic.AtomicLong nextAllowedTimeNanos = new java.util.concurrent.atomic.AtomicLong(0L);
    boolean sslVerify = properties.defaultConfig().sslVerify();
    this.protocolHandlers = Map.of(
        "HTTP", new HttpProtocolHandler(mapper, clock, metricsRecorder, httpClient, noKeepAliveClient, perThreadClient, nextAllowedTimeNanos),
        "TCP", new TcpProtocolHandler(mapper, clock, metricsRecorder, properties.defaultConfig().tcpTransport(), nextAllowedTimeNanos)
    );
  }


  @Override
  public WorkItem onMessage(WorkItem in, WorkerContext context) {
    ProcessorWorkerConfig config = context.configOrDefault(ProcessorWorkerConfig.class, properties::defaultConfig);

    Logger logger = context.logger();
    try {
      WorkItem response = invoke(in, config, context);
      publishStatus(context, config);
      return response;
    } catch (ProcessorCallException ex) {
      String message = ex.getCause() != null ? ex.getCause().getMessage() : ex.getMessage();
      logger.warn("Processor request failed: {}", ex.getCause() != null ? ex.getCause().toString() : ex.toString(), ex);
      WorkItem error = buildError(in, message, ex.metrics(), context);
      publishStatus(context, config);
      return error;
    } catch (Exception ex) {
      logger.warn("Processor request failed: {}", ex.toString(), ex);
      WorkItem error = buildError(in, ex.toString(), CallMetrics.failure(0L, 0L, -1), context);
      publishStatus(context, config);
      return error;
    }
  }

  private WorkItem invoke(WorkItem message, ProcessorWorkerConfig config, WorkerContext context) throws Exception {
    JsonNode envelope = message.asJsonNode();
    String protocol = envelope.path("protocol").asText("HTTP").toUpperCase();
    ProtocolHandler handler = protocolHandlers.get(protocol);
    if (handler == null) {
      throw new ProcessorCallException(CallMetrics.failure(0L, 0L, -1),
          new IllegalArgumentException("Unsupported protocol: " + protocol));
    }
    return handler.invoke(message, envelope, config, context);
  }

  private WorkItem buildError(WorkItem in, String message, CallMetrics metrics, WorkerContext context) {
    ObjectNode result = mapper.createObjectNode();
    result.put("error", message);
    WorkItem errorItem = ResponseBuilder.build(result, context.info(), metrics);
    WorkItem updated = in.addStep(context.info(), errorItem.asString(), errorItem.stepHeaders());
    return updated.toBuilder().contentType(errorItem.contentType()).build();
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
            .data("transactions", metricsRecorder.totalCalls())
            .data("successRatio", metricsRecorder.successRatio())
            .data("avgLatencyMs", metricsRecorder.averageLatencyMs()));
  }

  private int httpMaxConnections(ProcessorWorkerConfig config) {
    if (!Boolean.TRUE.equals(config.keepAlive())) return 0;
    ProcessorWorkerConfig.ConnectionReuse reuse = config.connectionReuse();
    return reuse == ProcessorWorkerConfig.ConnectionReuse.GLOBAL ? GLOBAL_MAX_CONNECTIONS
        : reuse == ProcessorWorkerConfig.ConnectionReuse.PER_THREAD ? config.threadCount() : 0;
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
}
