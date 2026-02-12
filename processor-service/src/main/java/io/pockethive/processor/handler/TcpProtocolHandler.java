package io.pockethive.processor.handler;

import io.pockethive.processor.ProcessorWorkerConfig;
import io.pockethive.processor.ResultRulesExtractor;
import io.pockethive.processor.TcpTransportConfig;
import io.pockethive.processor.metrics.CallMetrics;
import io.pockethive.processor.metrics.CallMetricsRecorder;
import io.pockethive.processor.exception.ProcessorCallException;
import io.pockethive.processor.response.ResponseBuilder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.pockethive.processor.transport.*;
import io.pockethive.worker.sdk.api.WorkItem;
import io.pockethive.worker.sdk.api.WorkerContext;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
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
    TcpTransportConfig desired = processorConfig.tcpTransport() == null ? defaultConfig : processorConfig.tcpTransport();
    ensureTransportConfig(desired);

    String baseUrl = processorConfig.baseUrl();
    if (baseUrl == null || baseUrl.isBlank()) {
      throw new ProcessorCallException(CallMetrics.failure(0L, 0L, -1),
          new IllegalArgumentException("invalid TCP baseUrl"));
    }

    boolean useSsl = baseUrl.startsWith("tcps://");
    String[] parts = parseUrl(baseUrl);
    String host = parts[0];
    int port = Integer.parseInt(parts[1]);

    Optional<String> body = extractBody(envelope.path("body"));
    if (body.isEmpty()) {
      throw new ProcessorCallException(CallMetrics.failure(0L, 0L, -1),
          new IllegalArgumentException("no TCP body"));
    }
    String requestBody = body.get();
    Map<String, String> requestHeaders = requestHeaders(envelope.path("headers"));

    TcpBehavior behavior = TcpBehavior.valueOf(envelope.path("behavior").asText("REQUEST_RESPONSE"));
    String endTag = envelope.path("endTag").asText(null);
    Integer maxBytes = envelope.path("maxBytes").isInt() ? envelope.path("maxBytes").asInt() : null;

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
      TcpRequest request = new TcpRequest(host, port, body.get().getBytes(StandardCharsets.UTF_8), options);

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
          response = transport.execute(request, behavior);
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
      String responseBody = new String(response.body(), StandardCharsets.UTF_8);
      result.put("body", responseBody);

      Map<String, Object> extractionHeaders = ResultRulesExtractor.extract(
          mapper,
          envelope,
          requestBody,
          requestHeaders,
          responseBody,
          Map.of());

      WorkItem responseItem = ResponseBuilder.build(result, context.info().role(), metrics, extractionHeaders);
      return message.addStep(responseItem.asString(), responseItem.headers());
    } catch (Exception ex) {
      long now = clock.millis();
      long totalDuration = Math.max(0L, now - start);
      long callDuration = Math.max(0L, totalDuration - pacingMillis);
      long connectionLatency = Math.max(0L, pacingMillis);
      CallMetrics metrics = CallMetrics.failure(callDuration, connectionLatency, -1);
      metricsRecorder.record(metrics);
      throw new ProcessorCallException(metrics, ex);
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

  private Optional<String> extractBody(JsonNode bodyNode) throws Exception {
    return bodyNode == null || bodyNode.isMissingNode() || bodyNode.isNull() ? Optional.empty()
        : bodyNode.isTextual() ? Optional.of(bodyNode.asText())
        : Optional.of(mapper.writeValueAsString(bodyNode));
  }

  private Map<String, String> requestHeaders(JsonNode headersNode) {
    if (headersNode == null || !headersNode.isObject()) {
      return Map.of();
    }
    Map<String, String> headers = new java.util.LinkedHashMap<>();
    headersNode.fields().forEachRemaining(entry -> headers.put(entry.getKey(), entry.getValue().asText()));
    return Map.copyOf(headers);
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
