package io.pockethive.processor.handler;

import io.pockethive.processor.ProcessorWorkerConfig;
import io.pockethive.processor.TcpTransportConfig;
import io.pockethive.processor.metrics.CallMetrics;
import io.pockethive.processor.metrics.CallMetricsRecorder;
import io.pockethive.processor.exception.ProcessorCallException;
import io.pockethive.processor.response.ResponseBuilder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.pockethive.worker.sdk.api.TcpRequestEnvelope;
import io.pockethive.processor.transport.*;
import io.pockethive.worker.sdk.api.WorkItem;
import io.pockethive.worker.sdk.api.WorkerContext;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

public class TcpProtocolHandler implements ProtocolHandler {
  private final ObjectMapper mapper;
  private final Clock clock;
  private final CallMetricsRecorder metricsRecorder;
  private final TcpTransportConfig defaultConfig;
  private final AtomicLong nextAllowedTimeNanos;
  private final Object transportLock = new Object();

  private volatile TcpTransportConfig activeConfig;
  private volatile TcpTransport globalTransport;
  private volatile PerThreadTransportPool perThreadTransportPool;

  public TcpProtocolHandler(ObjectMapper mapper,
                            Clock clock,
                            CallMetricsRecorder metricsRecorder,
                            TcpTransportConfig defaultConfig,
                            AtomicLong nextAllowedTimeNanos) {
    this.mapper = mapper;
    this.clock = clock;
    this.metricsRecorder = metricsRecorder;
    this.defaultConfig = defaultConfig == null ? TcpTransportConfig.defaults() : defaultConfig;
    this.nextAllowedTimeNanos = nextAllowedTimeNanos == null ? new AtomicLong(0L) : nextAllowedTimeNanos;
    reloadTransports(this.defaultConfig);
  }

  @Override
  public WorkItem invoke(WorkItem message, JsonNode envelope, ProcessorWorkerConfig processorConfig, WorkerContext context) throws Exception {
    String baseUrl = processorConfig.baseUrl();
    Map<String, Object> requestMeta = requestMetadata(baseUrl, null, null, null, null);

    TcpRequestEnvelope.TcpRequest requestEnvelope;
    try {
      requestEnvelope = parseRequest(envelope);
    } catch (IllegalArgumentException ex) {
      throw new ProcessorCallException(CallMetrics.failure(0L, 0L, -1), ex, requestMeta);
    }

    TcpTransportConfig desired = processorConfig.tcpTransport() == null ? defaultConfig : processorConfig.tcpTransport();
    ensureTransportConfig(desired);

    requestMeta = requestMetadata(baseUrl, null, null, requestEnvelope.behavior(), null);
    if (baseUrl == null || baseUrl.isBlank()) {
      throw new ProcessorCallException(CallMetrics.failure(0L, 0L, -1),
          new IllegalArgumentException("invalid TCP baseUrl"), requestMeta);
    }

    boolean useSsl = baseUrl.startsWith("tcps://");
    String host;
    int port;
    try {
      String[] parts = parseUrl(baseUrl);
      host = parts[0];
      port = Integer.parseInt(parts[1]);
    } catch (Exception ex) {
      throw new ProcessorCallException(CallMetrics.failure(0L, 0L, -1),
          new IllegalArgumentException("invalid TCP baseUrl"), requestMeta);
    }
    requestMeta = requestMetadata(baseUrl, host, port, requestEnvelope.behavior(), useSsl);

    Optional<String> body = extractBody(requestEnvelope.body());
    if (body.isEmpty()) {
      throw new ProcessorCallException(CallMetrics.failure(0L, 0L, -1),
          new IllegalArgumentException("no TCP body"), requestMeta);
    }

    TcpBehavior behavior;
    try {
      behavior = TcpBehavior.valueOf(requestEnvelope.behavior().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException ex) {
      throw new ProcessorCallException(CallMetrics.failure(0L, 0L, -1), ex, requestMeta);
    }
    String endTag = requestEnvelope.endTag();
    Integer maxBytes = requestEnvelope.maxBytes();

    long start = clock.millis();
    long pacingMillis = 0L;
    TcpTransport transport = null;
    boolean closeAfter = false;
    try {
      pacingMillis = applyExecutionMode(processorConfig);

      TcpTransportConfig config = activeConfig;
      var options = new java.util.HashMap<String, Object>();
      if (endTag != null) {
        options.put("endTag", endTag);
      }
      options.put("connectTimeoutMs", config.connectTimeoutMs());
      options.put("readTimeoutMs", config.readTimeoutMs());
      options.put("maxBytes", maxBytes != null ? maxBytes : config.maxBytes());
      options.put("ssl", useSsl);
      options.put("sslVerify", config.sslVerify());
      TcpRequest tcpRequest = new TcpRequest(host, port, body.get().getBytes(StandardCharsets.UTF_8), options);

      // Connection reuse strategy
      transport = switch (config.connectionReuse()) {
        case PER_THREAD -> perThreadTransportPool.get();
        case GLOBAL -> globalTransport;
        case NONE -> {
          closeAfter = true;
          yield TcpTransportFactory.create(config);
        }
      };

      // Retry logic
      TcpResponse response = null;
      Exception lastException = null;

      for (int attempt = 0; attempt <= config.maxRetries(); attempt++) {
        try {
          response = transport.execute(tcpRequest, behavior);
          break;
        } catch (Exception ex) {
          lastException = ex;
          if (attempt < config.maxRetries()) {
            context.logger().warn("TCP attempt {} failed, retrying: {}", attempt + 1, ex.getMessage());
            Thread.sleep(100 * (attempt + 1)); // Exponential backoff
          }
        }
      }

      if (response == null) {
        throw lastException;
      }

      long now = clock.millis();
      long totalDuration = Math.max(0L, now - start);
      long callDuration = Math.max(0L, totalDuration - pacingMillis);
      long connectionLatency = Math.max(0L, pacingMillis);
      CallMetrics metrics = CallMetrics.success(callDuration, connectionLatency, response.status());
      metricsRecorder.record(metrics);

      ObjectNode result = mapper.createObjectNode();
      result.put("status", response.status());
      result.put("body", new String(response.body(), StandardCharsets.UTF_8));

      WorkItem responseItem = ResponseBuilder.build(result, context.info(), metrics);
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
    } finally {
      if (closeAfter && transport != null) {
        try {
          transport.close();
        } catch (Exception ignored) {
        }
      }
    }
  }

  private String[] parseUrl(String baseUrl) {
    String url = baseUrl.startsWith("tcps://") ? baseUrl.substring(7)
        : baseUrl.startsWith("tcp://") ? baseUrl.substring(6)
        : baseUrl;
    return url.split(":");
  }

  private Map<String, Object> requestMetadata(String baseUrl, String host, Integer port, String behavior, Boolean useSsl) {
    Map<String, Object> request = new LinkedHashMap<>();
    String scheme = Boolean.TRUE.equals(useSsl) ? "tcps" : "tcp";
    request.put("transport", "tcp");
    request.put("scheme", scheme);
    request.put("method", normalizeMethod(behavior));
    request.put("baseUrl", baseUrl == null ? "" : baseUrl);
    request.put("path", "/");
    if (host != null && port != null) {
      request.put("url", scheme + "://" + host + ":" + port);
    }
    return request;
  }

  private String normalizeMethod(String behavior) {
    if (behavior == null || behavior.isBlank()) {
      return "TCP";
    }
    return behavior.trim().toUpperCase(Locale.ROOT);
  }

  private TcpRequestEnvelope.TcpRequest parseRequest(JsonNode envelope) {
    try {
      TcpRequestEnvelope parsed = mapper.treeToValue(envelope, TcpRequestEnvelope.class);
      return parsed.request();
    } catch (Exception ex) {
      throw new IllegalArgumentException("Invalid TCP request envelope", ex);
    }
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

  private void ensureTransportConfig(TcpTransportConfig desired) {
    if (desired == null) {
      desired = defaultConfig;
    }
    TcpTransportConfig current = activeConfig;
    if (desired.equals(current)) {
      return;
    }
    synchronized (transportLock) {
      if (!desired.equals(activeConfig)) {
        reloadTransports(desired);
      }
    }
  }

  private void reloadTransports(TcpTransportConfig config) {
    TcpTransport previousGlobal = this.globalTransport;
    PerThreadTransportPool previousPerThread = this.perThreadTransportPool;

    this.activeConfig = config;
    this.globalTransport = TcpTransportFactory.create(config);
    this.perThreadTransportPool = new PerThreadTransportPool(config);

    if (previousGlobal != null) {
      try {
        previousGlobal.close();
      } catch (Exception ignored) {
      }
    }
    if (previousPerThread != null) {
      previousPerThread.closeAll();
    }
  }

  private long applyExecutionMode(ProcessorWorkerConfig config) throws InterruptedException {
    ProcessorWorkerConfig.Mode mode = config.mode();
    if (mode == ProcessorWorkerConfig.Mode.RATE_PER_SEC) {
      double rate = config.ratePerSec();
      if (rate <= 0.0) {
        return 0L;
      }
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

  private static final class PerThreadTransportPool {
    private final ConcurrentLinkedQueue<TcpTransport> created = new ConcurrentLinkedQueue<>();
    private final ThreadLocal<TcpTransport> transport;

    private PerThreadTransportPool(TcpTransportConfig config) {
      this.transport = ThreadLocal.withInitial(() -> {
        TcpTransport createdTransport = TcpTransportFactory.create(config);
        created.add(createdTransport);
        return createdTransport;
      });
    }

    private TcpTransport get() {
      return transport.get();
    }

    private void closeAll() {
      for (TcpTransport transport : created) {
        try {
          transport.close();
        } catch (Exception ignored) {
        }
      }
      created.clear();
    }
  }
}
