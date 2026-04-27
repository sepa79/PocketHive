package io.pockethive.processor.handler;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.ObjectReader;
import io.pockethive.processor.ProcessorWorkerConfig;
import io.pockethive.processor.TcpTransportConfig;
import io.pockethive.processor.ResultRulesExtractor;
import io.pockethive.processor.exception.ProcessorCallException;
import io.pockethive.processor.metrics.CallMetrics;
import io.pockethive.processor.metrics.CallMetricsRecorder;
import io.pockethive.processor.response.ResponseBuilder;
import io.pockethive.processor.transport.TcpBehavior;
import io.pockethive.processor.transport.TcpRequest;
import io.pockethive.processor.transport.TcpResponse;
import io.pockethive.processor.transport.TcpTransport;
import io.pockethive.processor.transport.TcpTransportFactory;
import io.pockethive.worker.sdk.api.Iso8583RequestEnvelope;
import io.pockethive.worker.sdk.api.Iso8583ResultEnvelope;
import io.pockethive.worker.sdk.api.WorkItem;
import io.pockethive.worker.sdk.api.WorkerContext;
import java.net.URI;
import java.time.Clock;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

public class Iso8583ProtocolHandler implements ProtocolHandler {
  private final ObjectMapper mapper;
  private final ObjectReader strictEnvelopeReader;
  private final Clock clock;
  private final CallMetricsRecorder metricsRecorder;
  private final TcpTransportConfig defaultConfig;
  private final AtomicLong nextAllowedTimeNanos;
  private final Object transportLock = new Object();

  private volatile TcpTransportConfig activeConfig;
  private volatile TcpTransport globalTransport;
  private volatile PerThreadTransportPool perThreadTransportPool;

  public Iso8583ProtocolHandler(ObjectMapper mapper,
                                Clock clock,
                                CallMetricsRecorder metricsRecorder,
                                TcpTransportConfig defaultConfig,
                                AtomicLong nextAllowedTimeNanos) {
    this.mapper = mapper;
    this.strictEnvelopeReader = mapper.readerFor(Iso8583RequestEnvelope.class)
        .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    this.clock = clock;
    this.metricsRecorder = metricsRecorder;
    this.defaultConfig = defaultConfig == null ? TcpTransportConfig.defaults() : defaultConfig;
    this.nextAllowedTimeNanos = nextAllowedTimeNanos == null ? new AtomicLong(0L) : nextAllowedTimeNanos;
    reloadTransports(this.defaultConfig);
  }

  @Override
  public WorkItem invoke(WorkItem message, JsonNode envelope, ProcessorWorkerConfig config, WorkerContext context)
      throws Exception {
    Iso8583RequestEnvelope requestEnvelope;
    try {
      requestEnvelope = parseEnvelope(envelope);
    } catch (IllegalArgumentException ex) {
      throw new ProcessorCallException(CallMetrics.failure(0L, 0L, -1), ex, Map.of("transport", "iso8583"));
    }
    Iso8583RequestEnvelope.Iso8583Request request = requestEnvelope.request();

    Endpoint endpoint;
    try {
      endpoint = parseEndpoint(config.baseUrl());
    } catch (IllegalArgumentException ex) {
      throw new ProcessorCallException(CallMetrics.failure(0L, 0L, -1), ex, Map.of("transport", "iso8583"));
    }

    WireProfile wireProfile;
    byte[] payloadBytes;
    try {
      wireProfile = WireProfile.fromId(request.wireProfileId());
      payloadBytes = decodePayload(request);
    } catch (IllegalArgumentException ex) {
      throw new ProcessorCallException(
          CallMetrics.failure(0L, 0L, -1),
          ex,
          requestMetadata(endpoint, request, null));
    }

    TcpTransportConfig desired = config.tcpTransport() == null ? defaultConfig : config.tcpTransport();
    ensureTransportConfig(desired);

    long start = clock.millis();
    long pacingMillis = 0L;
    TcpTransport transport = null;
    boolean closeAfter = false;
    try {
      pacingMillis = applyExecutionMode(config);

      TcpTransportConfig transportConfig = activeConfig;
      byte[] framedPayload = wireProfile.frame(payloadBytes);
      Map<String, Object> options = new HashMap<>();
      options.put("connectTimeoutMs", transportConfig.connectTimeoutMs());
      options.put("readTimeoutMs", transportConfig.readTimeoutMs());
      options.put("maxBytes", transportConfig.maxBytes());
      options.put("ssl", "tcps".equals(endpoint.scheme()));
      options.put("sslVerify", transportConfig.sslVerify());
      TcpRequest tcpRequest = new TcpRequest(endpoint.host(), endpoint.port(), framedPayload, options);

      transport = switch (transportConfig.connectionReuse()) {
        case PER_THREAD -> perThreadTransportPool.get();
        case GLOBAL -> globalTransport;
        case NONE -> {
          closeAfter = true;
          yield TcpTransportFactory.create(transportConfig);
        }
      };

      TcpResponse response = null;
      Exception lastException = null;
      for (int attempt = 0; attempt <= transportConfig.maxRetries(); attempt++) {
        try {
          response = transport.execute(tcpRequest, TcpBehavior.LENGTH_PREFIX_2B);
          break;
        } catch (Exception ex) {
          lastException = ex;
          if (attempt < transportConfig.maxRetries()) {
            context.logger().warn("ISO8583 attempt {} failed, retrying: {}", attempt + 1, ex.getMessage());
            Thread.sleep(100L * (attempt + 1));
          }
        }
      }
      if (response == null) {
        throw lastException;
      }

      long end = clock.millis();
      long totalDuration = Math.max(0L, end - start);
      long callDuration = Math.max(0L, totalDuration - pacingMillis);
      long connectionLatency = Math.max(0L, pacingMillis);
      CallMetrics metrics = CallMetrics.success(callDuration, connectionLatency, 200);
      metricsRecorder.record(metrics);

      Iso8583ResultEnvelope resultEnvelope = Iso8583ResultEnvelope.of(
          new Iso8583ResultEnvelope.Iso8583RequestInfo(
              "iso8583",
              endpoint.scheme(),
              "SEND",
              endpoint.endpoint(),
              wireProfile.id(),
              request.payloadAdapter(),
              payloadBytes.length
          ),
          new Iso8583ResultEnvelope.Iso8583Outcome(
              Iso8583ResultEnvelope.OUTCOME_ISO8583_RESPONSE,
              200,
              HexFormat.of().withUpperCase().formatHex(response.body()),
              null
          ),
          new Iso8583ResultEnvelope.Iso8583Metrics(metrics.durationMs(), metrics.connectionLatencyMs())
      );

      ObjectNode result = mapper.valueToTree(resultEnvelope);
      String responseHex = resultEnvelope.outcome().responseHex();
      Map<String, Object> extractionHeaders = ResultRulesExtractor.extract(
          requestEnvelope.resultRules(),
          request.payload(),
          request.headers(),
          responseHex,
          Map.of()
      );

      WorkItem responseItem = ResponseBuilder.build(result, context.info(), metrics, extractionHeaders);
      WorkItem updated = message.addStep(context.info(), responseItem.asString(), responseItem.stepHeaders());
      return updated.toBuilder().contentType(responseItem.contentType()).build();
    } catch (Exception ex) {
      long end = clock.millis();
      long totalDuration = Math.max(0L, end - start);
      long callDuration = Math.max(0L, totalDuration - pacingMillis);
      long connectionLatency = Math.max(0L, pacingMillis);
      CallMetrics metrics = CallMetrics.failure(callDuration, connectionLatency, -1);
      metricsRecorder.record(metrics);
      throw new ProcessorCallException(metrics, ex, requestMetadata(endpoint, request, wireProfile));
    } finally {
      if (closeAfter && transport != null) {
        try {
          transport.close();
        } catch (Exception ignored) {
        }
      }
    }
  }

  private Iso8583RequestEnvelope parseEnvelope(JsonNode envelope) {
    try {
      return strictEnvelopeReader.readValue(envelope);
    } catch (Exception ex) {
      // Intentionally fail-loud: malformed envelope/resultRules must not be silently ignored.
      throw new IllegalArgumentException("Invalid ISO8583 request envelope", ex);
    }
  }

  private byte[] decodePayload(Iso8583RequestEnvelope.Iso8583Request request) {
    return switch (request.payloadAdapter()) {
      case "RAW_HEX" -> decodeRawHexPayload(request.payload());
      default -> throw new IllegalArgumentException("Unsupported ISO8583 payloadAdapter: " + request.payloadAdapter());
    };
  }

  private byte[] decodeRawHexPayload(String payload) {
    if (payload == null || payload.isBlank()) {
      throw new IllegalArgumentException("RAW_HEX payload must not be blank");
    }
    // Intentionally fail-loud: do not normalise/strip whitespace from payloads.
    // If callers need readability, they must pre-process upstream and pass clean RAW_HEX.
    for (int i = 0; i < payload.length(); i++) {
      if (Character.isWhitespace(payload.charAt(i))) {
        throw new IllegalArgumentException("RAW_HEX payload must not contain whitespace");
      }
    }
    if ((payload.length() & 1) != 0) {
      throw new IllegalArgumentException("Invalid RAW_HEX payload length");
    }
    return HexFormat.of().parseHex(payload);
  }

  private Endpoint parseEndpoint(String baseUrl) {
    if (baseUrl == null || baseUrl.isBlank()) {
      throw new IllegalArgumentException("invalid ISO8583 baseUrl");
    }
    URI uri;
    try {
      uri = URI.create(baseUrl.trim());
    } catch (Exception ex) {
      throw new IllegalArgumentException("invalid ISO8583 baseUrl", ex);
    }
    String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
    if (!"tcp".equals(scheme) && !"tcps".equals(scheme)) {
      throw new IllegalArgumentException("invalid ISO8583 baseUrl");
    }
    String host = uri.getHost();
    int port = uri.getPort();
    if (host == null || host.isBlank() || port <= 0) {
      throw new IllegalArgumentException("invalid ISO8583 baseUrl");
    }
    return new Endpoint(scheme, host, port);
  }

  private Map<String, Object> requestMetadata(Endpoint endpoint,
                                              Iso8583RequestEnvelope.Iso8583Request request,
                                              WireProfile profile) {
    Map<String, Object> requestMeta = new LinkedHashMap<>();
    requestMeta.put("transport", "iso8583");
    requestMeta.put("endpoint", endpoint.endpoint());
    requestMeta.put("scheme", endpoint.scheme());
    requestMeta.put("payloadAdapter", request.payloadAdapter());
    requestMeta.put("wireProfileId", profile == null ? request.wireProfileId() : profile.id());
    return requestMeta;
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
    }
    return 0L;
  }

  private record Endpoint(String scheme, String host, int port) {
    private String endpoint() {
      return scheme + "://" + host + ":" + port;
    }
  }

  private enum WireProfile {
    MC_2BYTE_LEN_BIN_BITMAP("MC_2BYTE_LEN_BIN_BITMAP");

    private final String id;

    WireProfile(String id) {
      this.id = id;
    }

    static WireProfile fromId(String id) {
      if (id == null || id.isBlank()) {
        throw new IllegalArgumentException("wireProfileId must not be blank");
      }
      String normalized = id.trim().toUpperCase(Locale.ROOT);
      for (WireProfile profile : values()) {
        if (profile.id.equals(normalized)) {
          return profile;
        }
      }
      throw new IllegalArgumentException("Unsupported ISO8583 wireProfileId: " + id);
    }

    byte[] frame(byte[] payload) {
      if (payload.length > 65535) {
        throw new IllegalArgumentException("ISO8583 payload exceeds 65535 bytes");
      }
      byte[] framed = new byte[2 + payload.length];
      framed[0] = (byte) ((payload.length >> 8) & 0xFF);
      framed[1] = (byte) (payload.length & 0xFF);
      System.arraycopy(payload, 0, framed, 2, payload.length);
      return framed;
    }

    String id() {
      return id;
    }
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
