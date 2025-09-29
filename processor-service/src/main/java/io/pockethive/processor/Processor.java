package io.pockethive.processor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.pockethive.Topology;
import io.pockethive.control.CommandState;
import io.pockethive.control.ControlSignal;
import io.pockethive.controlplane.ControlPlaneIdentity;
import io.pockethive.controlplane.messaging.ControlPlaneEmitter;
import io.pockethive.controlplane.routing.ControlPlaneRouting;
import io.pockethive.controlplane.topology.ControlPlaneRouteCatalog;
import io.pockethive.controlplane.topology.ControlPlaneTopologyDescriptor;
import io.pockethive.controlplane.topology.ControlQueueDescriptor;
import io.pockethive.controlplane.worker.WorkerConfigCommand;
import io.pockethive.controlplane.worker.WorkerControlPlane;
import io.pockethive.controlplane.worker.WorkerSignalListener;
import io.pockethive.controlplane.worker.WorkerSignalListener.WorkerSignalContext;
import io.pockethive.controlplane.worker.WorkerStatusRequest;
import io.pockethive.observability.ObservabilityContext;
import io.pockethive.observability.ObservabilityContextUtil;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.MessageListenerContainer;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import io.pockethive.control.ConfirmationScope;

@Component
@EnableScheduling
public class Processor {

  private static final Logger log = LoggerFactory.getLogger(Processor.class);
  private static final long STATUS_INTERVAL_MS = 5000L;
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final String CONFIG_PHASE = "apply";

  private final RabbitTemplate rabbit;
  private final ControlPlaneEmitter controlEmitter;
  private final WorkerControlPlane controlPlane;
  private final WorkerSignalListener controlListener;
  private final RabbitListenerEndpointRegistry registry;
  private final DistributionSummary sutLatency;
  private final Counter messageCounter;
  private final HttpClient http = HttpClient.newHttpClient();
  private final ControlPlaneIdentity identity;
  private final ConfirmationScope confirmationScope;
  private final String controlQueueName;
  private final String[] controlRoutes;
  private final String role;
  private final String swarmId;
  private final String instanceId;

  private double lastCount = 0;
  private volatile String baseUrl;
  private volatile boolean enabled = false;
  private volatile long lastStatusTs = System.currentTimeMillis();

  public Processor(RabbitTemplate rabbit,
                   MeterRegistry meterRegistry,
                   @Qualifier("workerControlPlaneIdentity") ControlPlaneIdentity identity,
                   ControlPlaneEmitter controlEmitter,
                   @Qualifier("workerControlPlaneTopologyDescriptor") ControlPlaneTopologyDescriptor topology,
                   WorkerControlPlane controlPlane,
                   String baseUrl,
                   RabbitListenerEndpointRegistry listenerRegistry) {
    this.rabbit = Objects.requireNonNull(rabbit, "rabbit");
    this.identity = Objects.requireNonNull(identity, "identity");
    this.controlEmitter = Objects.requireNonNull(controlEmitter, "controlEmitter");
    this.controlPlane = Objects.requireNonNull(controlPlane, "controlPlane");
    this.registry = Objects.requireNonNull(listenerRegistry, "listenerRegistry");
    this.baseUrl = baseUrl;
    this.role = identity.role();
    this.swarmId = identity.swarmId();
    this.instanceId = identity.instanceId();
    this.confirmationScope = new ConfirmationScope(swarmId, role, instanceId);

    ControlPlaneTopologyDescriptor descriptor = Objects.requireNonNull(topology, "topology");
    this.controlQueueName = descriptor.controlQueue(instanceId)
        .map(ControlQueueDescriptor::name)
        .orElseThrow(() -> new IllegalStateException("Processor control queue descriptor is missing"));
    this.controlRoutes = resolveRoutes(descriptor, identity);

    this.sutLatency = DistributionSummary.builder("processor_request_time_ms")
        .tag("service", role)
        .tag("instance", instanceId)
        .tag("swarm", swarmId)
        .register(Objects.requireNonNull(meterRegistry, "meterRegistry"));
    this.messageCounter = Counter.builder("processor_messages_total")
        .tag("service", role)
        .tag("instance", instanceId)
        .tag("swarm", swarmId)
        .register(meterRegistry);

    this.controlListener = new WorkerSignalListener() {
      @Override
      public void onStatusRequest(WorkerStatusRequest request) {
        ControlSignal signal = request.signal();
        if (signal.correlationId() != null) {
          MDC.put("correlation_id", signal.correlationId());
        }
        if (signal.idempotencyKey() != null) {
          MDC.put("idempotency_key", signal.idempotencyKey());
        }
        logControlReceive(request.envelope().routingKey(), signal.signal(), request.payload());
        sendStatusFull(0);
      }

      @Override
      public void onConfigUpdate(WorkerConfigCommand command) {
        ControlSignal signal = command.signal();
        if (signal.correlationId() != null) {
          MDC.put("correlation_id", signal.correlationId());
        }
        if (signal.idempotencyKey() != null) {
          MDC.put("idempotency_key", signal.idempotencyKey());
        }
        logControlReceive(command.envelope().routingKey(), signal.signal(), command.payload());
        handleConfigUpdate(command);
      }

      @Override
      public void onUnsupported(WorkerSignalContext context) {
        log.debug("Ignoring unsupported control signal {}", context.envelope().signal().signal());
      }
    };

    log.info("Base URL: {}", baseUrl);
    try {
      sendStatusFull(0);
    } catch (Exception ignore) {
      // best-effort during startup
    }
  }

  @RabbitListener(id = "workListener", queues = "${ph.modQueue:ph.default.mod}")
  public void onModerated(Message message,
                          @Header(value = ObservabilityContextUtil.HEADER, required = false) String trace) {
    Instant received = Instant.now();
    ObservabilityContext ctx = ObservabilityContextUtil.fromHeader(trace);
    if (ctx == null) {
      ctx = ObservabilityContextUtil.init("processor", instanceId);
      ctx.getHops().clear();
    }
    ObservabilityContextUtil.populateMdc(ctx);
    try {
      if (enabled) {
        String raw = new String(message.getBody(), StandardCharsets.UTF_8);
        log.debug("Forwarding to SUT: {}", raw);
        byte[] resp = sendToSut(message.getBody());
        Instant processed = Instant.now();
        ObservabilityContextUtil.appendHop(ctx, "processor", instanceId, received, processed);
        Message out = MessageBuilder
            .withBody(resp)
            .setContentType(MessageProperties.CONTENT_TYPE_JSON)
            .setContentEncoding(StandardCharsets.UTF_8.name())
            .setHeader("x-ph-service", "processor")
            .setHeader(ObservabilityContextUtil.HEADER, ObservabilityContextUtil.toHeader(ctx))
            .build();
        rabbit.send(Topology.EXCHANGE, Topology.FINAL_QUEUE, out);
        messageCounter.increment();
      }
    } finally {
      MDC.clear();
    }
  }

  @Scheduled(fixedRate = STATUS_INTERVAL_MS)
  public void status() {
    long now = System.currentTimeMillis();
    long elapsed = now - lastStatusTs;
    lastStatusTs = now;
    double total = messageCounter.count();
    long tps = 0;
    if (elapsed > 0) {
      tps = (long) ((total - lastCount) * 1000 / elapsed);
    }
    lastCount = total;
    sendStatusDelta(tps);
  }

  @RabbitListener(queues = "#{@processorControlQueueName}")
  public void onControl(String payload,
                        @Header(AmqpHeaders.RECEIVED_ROUTING_KEY) String rk,
                        @Header(value = ObservabilityContextUtil.HEADER, required = false) String trace) {
    ObservabilityContext ctx = ObservabilityContextUtil.fromHeader(trace);
    ObservabilityContextUtil.populateMdc(ctx);
    try {
      if (rk == null || rk.isBlank()) {
        log.warn("Received control payload with null or blank routing key; payloadLength={}", payload == null ? null : payload.length());
        throw new IllegalArgumentException("Control routing key must not be null or blank");
      }
      if (payload == null || payload.isBlank()) {
        log.warn("Received control payload with null or blank body for routing key {}", rk);
        throw new IllegalArgumentException("Control payload must not be null or blank");
      }
      boolean handled = controlPlane.consume(payload, rk, controlListener);
      if (!handled) {
        log.debug("Ignoring control payload on routing key {}", rk);
      }
    } finally {
      MDC.clear();
    }
  }

  private void handleConfigUpdate(WorkerConfigCommand command) {
    ControlSignal cs = command.signal();
    try {
      applyConfig(command.data());
      emitConfigSuccess(cs);
    } catch (Exception e) {
      log.warn("config update", e);
      emitConfigError(cs, e);
    }
  }

  private void applyConfig(Map<String, Object> data) {
    if (data == null || data.isEmpty()) {
      return;
    }
    if (data.containsKey("enabled")) {
      boolean newEnabled = parseBoolean(data.get("enabled"), "enabled", enabled);
      if (newEnabled != enabled) {
        enabled = newEnabled;
        MessageListenerContainer c = registry.getListenerContainer("workListener");
        if (c != null) {
          if (enabled) {
            c.start();
          } else {
            c.stop();
          }
        }
      }
    }
    if (data.containsKey("baseUrl")) {
      baseUrl = parseString(data.get("baseUrl"), "baseUrl", baseUrl);
    }
  }

  private boolean parseBoolean(Object value, String field, boolean current) {
    if (value == null) {
      return current;
    }
    if (value instanceof Boolean b) {
      return b;
    }
    if (value instanceof String s) {
      if (s.isBlank()) {
        return current;
      }
      if ("true".equalsIgnoreCase(s) || "false".equalsIgnoreCase(s)) {
        return Boolean.parseBoolean(s);
      }
    }
    throw new IllegalArgumentException("Invalid %s value".formatted(field));
  }

  private String parseString(Object value, String field, String current) {
    if (value == null) {
      return current;
    }
    if (value instanceof String s) {
      return s;
    }
    throw new IllegalArgumentException("Invalid %s value type: %s".formatted(field, value.getClass().getSimpleName()));
  }

  private void emitConfigSuccess(ControlSignal cs) {
    String signal = requireText(cs.signal(), "signal");
    String correlationId = requireText(cs.correlationId(), "correlationId");
    String idempotencyKey = requireText(cs.idempotencyKey(), "idempotencyKey");
    CommandState state = currentState("completed");
    var context = ControlPlaneEmitter.ReadyContext.builder(signal, correlationId, idempotencyKey, state)
        .result("success")
        .build();
    String routingKey = ControlPlaneRouting.event("ready", signal, confirmationScope);
    logControlSend(routingKey, "result=success enabled=" + enabled);
    controlEmitter.emitReady(context);
  }

  private void emitConfigError(ControlSignal cs, Exception e) {
    String signal = requireText(cs.signal(), "signal");
    String correlationId = requireText(cs.correlationId(), "correlationId");
    String idempotencyKey = requireText(cs.idempotencyKey(), "idempotencyKey");
    String code = e.getClass().getSimpleName();
    String message = e.getMessage();
    if (message == null || message.isBlank()) {
      message = code;
    }
    CommandState state = currentState("failed");
    Map<String, Object> details = new LinkedHashMap<>(stateDetails());
    details.put("exception", code);
    var context = ControlPlaneEmitter.ErrorContext.builder(signal, correlationId, idempotencyKey, state, CONFIG_PHASE, code, message)
        .retryable(Boolean.FALSE)
        .result("error")
        .details(details)
        .build();
    String routingKey = ControlPlaneRouting.event("error", signal, confirmationScope);
    logControlSend(routingKey, "result=error code=" + code + " enabled=" + enabled);
    controlEmitter.emitError(context);
  }

  private void sendStatusDelta(long tps) {
    String routingKey = ControlPlaneRouting.event("status-delta", confirmationScope);
    logControlSend(routingKey, "tps=" + tps + " enabled=" + enabled + " baseUrl=" + baseUrl);
    controlEmitter.emitStatusDelta(statusContext(tps));
  }

  private void sendStatusFull(long tps) {
    String routingKey = ControlPlaneRouting.event("status-full", confirmationScope);
    logControlSend(routingKey, "tps=" + tps + " enabled=" + enabled + " baseUrl=" + baseUrl);
    controlEmitter.emitStatusSnapshot(statusContext(tps));
  }

  private ControlPlaneEmitter.StatusContext statusContext(long tps) {
    return ControlPlaneEmitter.StatusContext.of(builder -> {
      builder.traffic(Topology.EXCHANGE)
          .workIn(Topology.MOD_QUEUE)
          .workRoutes(Topology.MOD_QUEUE)
          .workOut(Topology.FINAL_QUEUE)
          .controlIn(controlQueueName)
          .controlRoutes(controlRoutes)
          .enabled(enabled)
          .tps(tps)
          .data("baseUrl", baseUrl);
    });
  }

  private CommandState currentState(String status) {
    return new CommandState(status, enabled, stateDetails());
  }

  private Map<String, Object> stateDetails() {
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("baseUrl", baseUrl);
    return details;
  }

  private static String[] resolveRoutes(ControlPlaneTopologyDescriptor descriptor, ControlPlaneIdentity identity) {
    ControlPlaneRouteCatalog routes = descriptor.routes();
    List<String> resolved = new ArrayList<>();
    resolved.addAll(expandRoutes(routes.configSignals(), identity));
    resolved.addAll(expandRoutes(routes.statusSignals(), identity));
    resolved.addAll(expandRoutes(routes.lifecycleSignals(), identity));
    resolved.addAll(expandRoutes(routes.statusEvents(), identity));
    resolved.addAll(expandRoutes(routes.lifecycleEvents(), identity));
    resolved.addAll(expandRoutes(routes.otherEvents(), identity));
    LinkedHashSet<String> unique = resolved.stream()
        .filter(route -> route != null && !route.isBlank())
        .collect(Collectors.toCollection(LinkedHashSet::new));
    return unique.toArray(String[]::new);
  }

  private static List<String> expandRoutes(Set<String> templates, ControlPlaneIdentity identity) {
    if (templates == null || templates.isEmpty()) {
      return List.of();
    }
    return templates.stream()
        .filter(Objects::nonNull)
        .map(route -> route.replace(ControlPlaneRouteCatalog.INSTANCE_TOKEN, identity.instanceId()))
        .toList();
  }

  private static String requireText(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be null or blank");
    }
    return value;
  }

  private void logControlReceive(String routingKey, String signal, String payload) {
    String snippet = snippet(payload);
    if (signal != null && signal.startsWith("status")) {
      log.debug("[CTRL] RECV rk={} inst={} payload={}", routingKey, instanceId, snippet);
    } else if ("config-update".equals(signal)) {
      log.info("[CTRL] RECV rk={} inst={} payload={}", routingKey, instanceId, snippet);
    } else {
      log.info("[CTRL] RECV rk={} inst={} payload={}", routingKey, instanceId, snippet);
    }
  }

  private void logControlSend(String routingKey, String details) {
    String snippet = details == null ? "" : details;
    if (routingKey.contains(".status-")) {
      log.debug("[CTRL] SEND rk={} inst={} {}", routingKey, instanceId, snippet);
    } else if (routingKey.contains(".config-update.")) {
      log.info("[CTRL] SEND rk={} inst={} {}", routingKey, instanceId, snippet);
    } else {
      log.info("[CTRL] SEND rk={} inst={} {}", routingKey, instanceId, snippet);
    }
  }

  private byte[] sendToSut(byte[] bodyBytes) {
    long start = System.currentTimeMillis();
    String method = "GET";
    URI target = null;
    try {
      JsonNode node = MAPPER.readTree(bodyBytes);
      String path = node.path("path").asText("/");
      method = node.path("method").asText("GET").toUpperCase();

      target = buildUri(path);
      if (target == null) {
        long dur = System.currentTimeMillis() - start;
        sutLatency.record(dur);
        return MAPPER.createObjectNode().put("error", "invalid baseUrl").toString().getBytes(StandardCharsets.UTF_8);
      }

      HttpRequest.Builder req = HttpRequest.newBuilder(target);

      JsonNode headers = node.path("headers");
      if (headers.isObject()) {
        headers.fields().forEachRemaining(e -> req.header(e.getKey(), e.getValue().asText()));
      }

      JsonNode bodyNode = node.path("body");
      HttpRequest.BodyPublisher bodyPublisher;
      String bodyStr = null;
      if (bodyNode.isMissingNode() || bodyNode.isNull()) {
        bodyPublisher = HttpRequest.BodyPublishers.noBody();
      } else if (bodyNode.isTextual()) {
        bodyStr = bodyNode.asText();
        bodyPublisher = HttpRequest.BodyPublishers.ofString(bodyStr, StandardCharsets.UTF_8);
      } else {
        bodyStr = MAPPER.writeValueAsString(bodyNode);
        bodyPublisher = HttpRequest.BodyPublishers.ofString(bodyStr, StandardCharsets.UTF_8);
      }

      req.method(method, bodyPublisher);

      String headersStr = headers.isObject() ? headers.toString() : "";
      String bodySnippet = bodyStr == null ? "" : (bodyStr.length() > 300 ? bodyStr.substring(0, 300) + "…" : bodyStr);
      log.debug("HTTP {} {} headers={} body={}", method, target, headersStr, bodySnippet);

      HttpResponse<String> resp = http.send(req.build(), HttpResponse.BodyHandlers.ofString());
      long dur = System.currentTimeMillis() - start;
      sutLatency.record(dur);
      log.debug("HTTP {} {} -> {} body={} headers={} ({} ms)", method, target, resp.statusCode(),
          snippet(resp.body()), resp.headers().map(), dur);

      var result = MAPPER.createObjectNode();
      result.put("status", resp.statusCode());
      result.set("headers", MAPPER.valueToTree(resp.headers().map()));
      result.put("body", resp.body());
      return MAPPER.writeValueAsBytes(result);
    } catch (Exception e) {
      long dur = System.currentTimeMillis() - start;
      sutLatency.record(dur);
      log.error("HTTP request failed for {} {}: {} ({} ms)", method, target, e.toString(), dur, e);
      return MAPPER.createObjectNode().put("error", e.toString()).toString().getBytes(StandardCharsets.UTF_8);
    }
  }

  private URI buildUri(String path) {
    String p = path == null ? "" : path;
    if (baseUrl == null || baseUrl.isBlank()) {
      log.warn("No baseUrl configured, cannot build target URI for path='{}'", p);
      return null;
    }
    try {
      return URI.create(baseUrl).resolve(p);
    } catch (Exception e) {
      log.warn("Invalid URI base='{}' path='{}'", baseUrl, p, e);
      return null;
    }
  }

  private static String snippet(String s) {
    if (s == null) {
      return "";
    }
    return s.length() > 300 ? s.substring(0, 300) + "…" : s;
  }
}
