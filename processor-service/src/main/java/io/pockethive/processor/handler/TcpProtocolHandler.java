package io.pockethive.processor.handler;

import io.pockethive.processor.ProcessorWorkerConfig;
import io.pockethive.processor.TcpTransportConfig;
import io.pockethive.processor.metrics.*;
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

public class TcpProtocolHandler implements ProtocolHandler {
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private final Clock clock;
  private final CallMetricsRecorder metricsRecorder;
  private final TcpTransport transport;
  private final TcpTransportConfig config;
  private final java.util.concurrent.atomic.AtomicLong nextAllowedTimeNanos = new java.util.concurrent.atomic.AtomicLong(0L);
  private final ThreadLocal<TcpTransport> perThreadTransport;

  public TcpProtocolHandler(Clock clock, CallMetricsRecorder metricsRecorder, TcpTransportConfig config) {
    this.clock = clock;
    this.metricsRecorder = metricsRecorder;
    this.config = config;
    this.transport = TcpTransportFactory.create(config.type());
    this.perThreadTransport = ThreadLocal.withInitial(() -> TcpTransportFactory.create(config.type()));
  }

  @Override
  public WorkItem invoke(WorkItem message, JsonNode envelope, ProcessorWorkerConfig processorConfig, WorkerContext context) throws Exception {

    // Rate limiting from worker config
    if (processorConfig.ratePerSec() > 0.0) {
      long intervalNanos = (long) (1_000_000_000.0 / processorConfig.ratePerSec());
      long now = System.nanoTime();
      long nextAllowed = nextAllowedTimeNanos.get();
      if (now < nextAllowed) {
        Thread.sleep((nextAllowed - now) / 1_000_000);
      }
      nextAllowedTimeNanos.set(now + intervalNanos);
    }
    String baseUrl = processorConfig.baseUrl();
    if (baseUrl == null || baseUrl.isBlank()) {
      throw new ProcessorCallException(CallMetrics.failure(0L, 0L, -1), new IllegalArgumentException("invalid TCP baseUrl"));
    }

    boolean useSsl = baseUrl.startsWith("tcps://");
    String[] parts = parseUrl(baseUrl);
    String host = parts[0];
    int port = Integer.parseInt(parts[1]);

    Optional<String> body = extractBody(envelope.path("body"));
    if (body.isEmpty()) {
      throw new ProcessorCallException(CallMetrics.failure(0L, 0L, -1), new IllegalArgumentException("no TCP body"));
    }

    TcpBehavior behavior = TcpBehavior.valueOf(envelope.path("behavior").asText("REQUEST_RESPONSE"));
    String endTag = envelope.path("endTag").asText("</Document>");

    TcpRequest request = new TcpRequest(host, port, body.get().getBytes(StandardCharsets.UTF_8),
        Map.of("endTag", endTag, "timeout", config.timeout(), "maxBytes", config.maxBytes(), 
               "ssl", useSsl, "sslVerify", config.sslVerify()));

    long start = clock.millis();

    // Connection reuse strategy
    TcpTransport activeTransport = switch (config.connectionReuse()) {
      case PER_THREAD -> perThreadTransport.get();
      case GLOBAL -> transport;
      case NONE -> TcpTransportFactory.create(config.type());
    };

    // Retry logic
    TcpResponse response = null;
    Exception lastException = null;

    for (int attempt = 0; attempt <= config.maxRetries(); attempt++) {
      try {
        response = activeTransport.execute(request, behavior);
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

    CallMetrics metrics = CallMetrics.success(response.durationMs(), 0L, response.status());
    metricsRecorder.record(metrics);

    ObjectNode result = MAPPER.createObjectNode();
    result.put("status", response.status());
    result.put("body", new String(response.body(), StandardCharsets.UTF_8));

    WorkItem responseItem = ResponseBuilder.build(result, context.info().role(), metrics);
    return message.addStep(responseItem.asString(), responseItem.headers());
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
        : Optional.of(MAPPER.writeValueAsString(bodyNode));
  }
}
