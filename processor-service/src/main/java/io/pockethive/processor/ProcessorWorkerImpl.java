package io.pockethive.processor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.processor.handler.ProtocolHandler;
import io.pockethive.processor.handler.HttpProtocolHandler;
import io.pockethive.processor.handler.Iso8583ProtocolHandler;
import io.pockethive.processor.handler.TcpProtocolHandler;
import io.pockethive.processor.metrics.CallMetricsRecorder;
import io.pockethive.processor.exception.ProcessorCallException;
import io.pockethive.worker.sdk.api.PocketHiveWorkerFunction;
import io.pockethive.worker.sdk.api.WorkItem;
import io.pockethive.worker.sdk.api.WorkerContext;
import io.pockethive.worker.sdk.config.PocketHiveWorker;
import io.pockethive.worker.sdk.config.WorkerCapability;

import java.time.Clock;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.apache.hc.client5.http.classic.HttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.ssl.NoopHostnameVerifier;
import org.apache.hc.client5.http.ssl.SSLConnectionSocketFactory;
import org.apache.hc.client5.http.ssl.SSLConnectionSocketFactoryBuilder;
import org.apache.hc.client5.http.ssl.TrustAllStrategy;
import org.apache.hc.core5.http.ConnectionReuseStrategy;
import org.apache.hc.core5.ssl.SSLContextBuilder;
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
 * Once configured, the worker performs an outbound call from a typed request envelope
 * (for example {@code kind=http.request} or {@code kind=tcp.request}). Successful calls append
 * a result step and emit a {@link WorkItem} to the configured final routing key
 * ({@code pockethive.outputs.rabbit.routing-key}). Runtime failures are propagated as exceptions;
 * the SDK transport layer handles them out-of-band (log + alert/journal + drop) instead of
 * emitting error payload steps.
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

  private record HttpClientBundle(HttpClient pooled, HttpClient noKeepAlive, ThreadLocal<HttpClient> perThread) {
  }

  private final ObjectMapper mapper;
  private final ProcessorWorkerProperties properties;
  private final CallMetricsRecorder metricsRecorder = new CallMetricsRecorder();
  private final Map<String, ProtocolHandler> protocolHandlers;

  @Autowired
  ProcessorWorkerImpl(ObjectMapper mapper, ProcessorWorkerProperties properties) {
    this(mapper, properties, newHttpClientBundle(true), newHttpClientBundle(false), Clock.systemUTC());
  }

  ProcessorWorkerImpl(ObjectMapper mapper, ProcessorWorkerProperties properties, HttpClient httpClient, HttpClient noKeepAliveClient, Clock clock) {
    this(mapper, properties,
        new HttpClientBundle(httpClient, noKeepAliveClient, ThreadLocal.withInitial(() -> httpClient)),
        new HttpClientBundle(httpClient, noKeepAliveClient, ThreadLocal.withInitial(() -> httpClient)),
        clock);
  }

  ProcessorWorkerImpl(ObjectMapper mapper,
                      ProcessorWorkerProperties properties,
                      HttpClient verifiedClient,
                      HttpClient verifiedNoKeepAliveClient,
                      HttpClient insecureClient,
                      HttpClient insecureNoKeepAliveClient,
                      Clock clock) {
    this(mapper, properties,
        new HttpClientBundle(verifiedClient, verifiedNoKeepAliveClient, ThreadLocal.withInitial(() -> verifiedClient)),
        new HttpClientBundle(insecureClient, insecureNoKeepAliveClient, ThreadLocal.withInitial(() -> insecureClient)),
        clock);
  }

  private ProcessorWorkerImpl(ObjectMapper mapper,
                              ProcessorWorkerProperties properties,
                              HttpClientBundle verifiedClients,
                              HttpClientBundle insecureClients,
                              Clock clock) {
    this.mapper = Objects.requireNonNull(mapper, "mapper");
    this.properties = Objects.requireNonNull(properties, "properties");
    java.util.concurrent.atomic.AtomicLong nextAllowedTimeNanos = new java.util.concurrent.atomic.AtomicLong(0L);
    this.protocolHandlers = Map.of(
        "HTTP", new HttpProtocolHandler(
            mapper,
            clock,
            metricsRecorder,
            verifiedClients.pooled(),
            verifiedClients.noKeepAlive(),
            verifiedClients.perThread(),
            insecureClients.pooled(),
            insecureClients.noKeepAlive(),
            insecureClients.perThread(),
            nextAllowedTimeNanos),
        "TCP", new TcpProtocolHandler(mapper, clock, metricsRecorder, properties.defaultConfig().tcpTransport(), nextAllowedTimeNanos),
        "ISO8583", new Iso8583ProtocolHandler(
            mapper,
            clock,
            metricsRecorder,
            properties.defaultConfig().tcpTransport(),
            nextAllowedTimeNanos)
    );
  }


  @Override
  public WorkItem onMessage(WorkItem in, WorkerContext context) {
    ProcessorWorkerConfig config = context.configOrDefault(ProcessorWorkerConfig.class, properties::defaultConfig);

    Logger logger = context.logger();
    try {
      return invoke(in, config, context);
    } catch (ProcessorCallException ex) {
      Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
      logger.warn("Processor request failed: {}", cause.toString(), ex);
      if (cause instanceof RuntimeException runtimeException) {
        throw runtimeException;
      }
      throw new IllegalStateException("Processor request failed", cause);
    } catch (RuntimeException ex) {
      logger.warn("Processor runtime failure: {}", ex.toString(), ex);
      throw ex;
    } catch (Exception ex) {
      logger.warn("Processor runtime failure: {}", ex.toString(), ex);
      throw new IllegalStateException("Processor runtime failure", ex);
    } finally {
      publishStatus(context, config);
    }
  }

  private WorkItem invoke(WorkItem message, ProcessorWorkerConfig config, WorkerContext context) throws Exception {
    JsonNode envelope = message.asJsonNode();
    String protocol = resolveProtocol(envelope);
    ProtocolHandler handler = protocolHandlers.get(protocol);
    if (handler == null) {
      throw new IllegalStateException("No protocol handler registered for protocol: " + protocol);
    }
    return handler.invoke(message, envelope, config, context);
  }

  private static String resolveProtocol(JsonNode envelope) {
    String kind = envelope.path("kind").asText("").trim().toLowerCase(Locale.ROOT);
    return switch (kind) {
      case "http.request" -> "HTTP";
      case "tcp.request" -> "TCP";
      case "iso8583.request" -> "ISO8583";
      case "" -> throw new IllegalArgumentException("Missing request kind");
      default -> throw new IllegalArgumentException("Unsupported request kind: " + kind);
    };
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

  private static HttpClientBundle newHttpClientBundle(boolean sslVerify) {
    return new HttpClientBundle(
        newHttpClient(sslVerify, true),
        newHttpClient(sslVerify, false),
        ThreadLocal.withInitial(() -> newHttpClient(sslVerify, true)));
  }

  private static HttpClient newHttpClient(boolean sslVerify, boolean keepAlive) {
    PoolingHttpClientConnectionManager manager = newConnectionManager(sslVerify);
    var builder = HttpClients.custom()
        .useSystemProperties()
        .setConnectionManager(manager);
    if (keepAlive) {
      return builder.build();
    }
    ConnectionReuseStrategy noReuse = (request, response, context) -> false;
    return builder
        .setConnectionReuseStrategy(noReuse)
        .build();
  }

  private static PoolingHttpClientConnectionManager newConnectionManager(boolean sslVerify) {
    PoolingHttpClientConnectionManagerBuilder builder = PoolingHttpClientConnectionManagerBuilder.create();
    if (sslVerify) {
      builder.useSystemProperties();
    } else {
      builder.setSSLSocketFactory(insecureSocketFactory());
    }
    PoolingHttpClientConnectionManager manager = builder.build();
    manager.setMaxTotal(GLOBAL_MAX_CONNECTIONS);
    manager.setDefaultMaxPerRoute(GLOBAL_MAX_PER_ROUTE);
    return manager;
  }

  private static SSLConnectionSocketFactory insecureSocketFactory() {
    try {
      return SSLConnectionSocketFactoryBuilder.create()
          .setSslContext(SSLContextBuilder.create().loadTrustMaterial(null, TrustAllStrategy.INSTANCE).build())
          .setHostnameVerifier(NoopHostnameVerifier.INSTANCE)
          .build();
    } catch (Exception ex) {
      throw new IllegalStateException("Failed to create insecure HTTP client SSL context", ex);
    }
  }
}
