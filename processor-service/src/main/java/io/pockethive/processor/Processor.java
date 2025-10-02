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
import io.pockethive.controlplane.messaging.ControlPlaneEmitter.ErrorContext;
import io.pockethive.controlplane.messaging.ControlPlaneEmitter.ReadyContext;
import io.pockethive.controlplane.routing.ControlPlaneRouting;
import io.pockethive.controlplane.topology.ControlPlaneTopologyDescriptor;
import io.pockethive.controlplane.worker.WorkerConfigCommand;
import io.pockethive.controlplane.worker.WorkerControlPlane;
import io.pockethive.observability.ObservabilityContext;
import io.pockethive.observability.ObservabilityContextUtil;
import io.pockethive.worker.runtime.AbstractWorkerRuntime;
import io.pockethive.worker.runtime.AbstractWorkerRuntime.ListenerLifecycle;
import io.pockethive.worker.runtime.WorkerMessageEnvelope;
import io.pockethive.worker.runtime.WorkerMessageEnvelopeCodec;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@EnableScheduling
public class Processor extends AbstractWorkerRuntime {

  private static final Logger log = LoggerFactory.getLogger(Processor.class);
  private static final long STATUS_INTERVAL_MS = 5000L;
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final String CONFIG_PHASE = "apply";

  private final RabbitTemplate rabbit;
  private final DistributionSummary sutLatency;
  private final Counter messageCounter;
  private final HttpClient http = HttpClient.newHttpClient();
  private final RabbitListenerEndpointRegistry registry;
  private final ListenerLifecycle workListenerLifecycle;
  private final WorkerMessageEnvelopeCodec envelopeCodec;

  private double lastCount = 0;
  private volatile String baseUrl;
  private volatile boolean enabled;
  private volatile long lastStatusTs = System.currentTimeMillis();

  public Processor(RabbitTemplate rabbit,
                   MeterRegistry meterRegistry,
                   @Qualifier("workerControlPlaneIdentity") ControlPlaneIdentity identity,
                   ControlPlaneEmitter controlEmitter,
                   @Qualifier("workerControlPlaneTopologyDescriptor") ControlPlaneTopologyDescriptor topology,
                   WorkerControlPlane controlPlane,
                   @Value("${ph.processor.baseUrl:}") String baseUrl,
                   RabbitListenerEndpointRegistry listenerRegistry) {
    super(log, controlEmitter, controlPlane, identity, topology);
    this.rabbit = Objects.requireNonNull(rabbit, "rabbit");
    this.registry = Objects.requireNonNull(listenerRegistry, "listenerRegistry");
    this.baseUrl = baseUrl;
    this.envelopeCodec = new WorkerMessageEnvelopeCodec(MAPPER);
    this.workListenerLifecycle = listenerLifecycle(
        () -> updateListenerState(true),
        () -> updateListenerState(false));

    String role = identity.role();
    String instanceId = identity.instanceId();
    String swarmId = identity.swarmId();
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
      ctx = ObservabilityContextUtil.init("processor", identity().instanceId());
      ctx.getHops().clear();
    }
    ObservabilityContextUtil.populateMdc(ctx);
    try {
      if (enabled) {
        WorkerMessageEnvelope envelope = decodeEnvelope(message);
        ObjectNode payload = envelope.payload();
        log.debug("Forwarding to SUT: {}", payload.toString());
        byte[] resp = sendToSut(payload);
        Instant processed = Instant.now();
        ObservabilityContextUtil.appendHop(ctx, "processor", identity().instanceId(), received, processed);
        Message out = MessageBuilder
            .withBody(resp)
            .setContentType(MessageProperties.CONTENT_TYPE_JSON)
            .setContentEncoding(StandardCharsets.UTF_8.name())
            .setMessageId(envelope.messageId())
            .setTimestamp(java.util.Date.from(envelope.timestamp()))
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
        log.warn("Received control payload with null or blank routing key; payloadLength={}",
            payload == null ? null : payload.length());
        throw new IllegalArgumentException("Control routing key must not be null or blank");
      }
      if (payload == null || payload.isBlank()) {
        log.warn("Received control payload with null or blank body for routing key {}", rk);
        throw new IllegalArgumentException("Control payload must not be null or blank");
      }
      boolean handled = controlPlane().consume(payload, rk, controlListener());
      if (!handled) {
        log.debug("Ignoring control payload on routing key {}", rk);
      }
    } finally {
      MDC.clear();
    }
  }

  @Override
  protected void handleConfigUpdate(WorkerConfigCommand command) {
    ControlSignal cs = command.signal();
    try {
      applyConfig(command.data());
      emitConfigSuccess(cs);
    } catch (Exception e) {
      log.warn("config update", e);
      emitConfigError(cs, e);
    }
  }

  @Override
  protected ControlPlaneEmitter.StatusContext statusContext(long tps) {
    return baseStatusContext(tps, builder -> builder
        .traffic(Topology.EXCHANGE)
        .workIn(Topology.MOD_QUEUE)
        .workRoutes(Topology.MOD_QUEUE)
        .workOut(Topology.FINAL_QUEUE)
        .enabled(enabled)
        .data("baseUrl", baseUrl));
  }

  @Override
  protected String statusLogDetails(long tps) {
    return super.statusLogDetails(tps) + " enabled=" + enabled + " baseUrl=" + baseUrl;
  }

  private void applyConfig(Map<String, Object> data) {
    if (data == null || data.isEmpty()) {
      return;
    }
    if (data.containsKey("enabled")) {
      boolean newEnabled = parseBoolean(data.get("enabled"), "enabled", enabled);
      boolean changed = newEnabled != enabled;
      enabled = newEnabled;
      if (changed) {
        workListenerLifecycle.apply(enabled);
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
    ReadyContext context = ReadyContext.builder(signal, correlationId, idempotencyKey, state)
        .result("success")
        .build();
    String routingKey = ControlPlaneRouting.event("ready", signal, confirmationScope());
    logControlSend(routingKey, "result=success enabled=" + enabled);
    controlEmitter().emitReady(context);
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
    ErrorContext context = ErrorContext.builder(signal, correlationId, idempotencyKey, state, CONFIG_PHASE, code, message)
        .retryable(Boolean.FALSE)
        .result("error")
        .details(details)
        .build();
    String routingKey = ControlPlaneRouting.event("error", signal, confirmationScope());
    logControlSend(routingKey, "result=error code=" + code + " enabled=" + enabled);
    controlEmitter().emitError(context);
  }

  private CommandState currentState(String status) {
    return new CommandState(status, enabled, stateDetails());
  }

  private Map<String, Object> stateDetails() {
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("baseUrl", baseUrl);
    return details;
  }

  private byte[] sendToSut(ObjectNode payload) {
    long start = System.currentTimeMillis();
    String method = "GET";
    URI target = null;
    try {
      String path = payload.path("path").asText("/");
      method = payload.path("method").asText("GET").toUpperCase();
      target = buildUri(path);
      if (target == null) {
        long dur = System.currentTimeMillis() - start;
        sutLatency.record(dur);
        return MAPPER.createObjectNode().put("error", "invalid baseUrl").toString().getBytes(StandardCharsets.UTF_8);
      }

      HttpRequest.Builder req = HttpRequest.newBuilder(target);

      JsonNode headers = payload.path("headers");
      if (headers.isObject()) {
        headers.fields().forEachRemaining(e -> req.header(e.getKey(), e.getValue().asText()));
      }

      JsonNode bodyNode = payload.path("body");
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

      ObjectNode result = MAPPER.createObjectNode();
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

  private WorkerMessageEnvelope decodeEnvelope(Message message) {
    ObjectNode node = readBody(message);
    MessageProperties properties = message.getMessageProperties();
    ensureMessageId(node, properties);
    ensureTimestamp(node, properties);
    putIfMissing(node, "role", identity().role());
    putIfMissing(node, "instance", identity().instanceId());
    putIfMissing(node, "swarmId", identity().swarmId());
    return envelopeCodec.decode(node);
  }

  private ObjectNode readBody(Message message) {
    try {
      JsonNode node = MAPPER.readTree(message.getBody());
      if (!node.isObject()) {
        throw new IllegalArgumentException("Moderated payload must be a JSON object");
      }
      return (ObjectNode) node;
    } catch (IOException e) {
      throw new IllegalArgumentException("Unable to parse moderated payload", e);
    }
  }

  private void ensureMessageId(ObjectNode node, MessageProperties properties) {
    String messageId = textOrNull(node.get("messageId"));
    if (messageId == null || messageId.isBlank()) {
      messageId = properties.getMessageId();
      if (messageId == null || messageId.isBlank()) {
        messageId = textOrNull(node.get("id"));
      }
      if (messageId == null || messageId.isBlank()) {
        throw new IllegalArgumentException("messageId must not be null or blank");
      }
      node.put("messageId", messageId);
    }
  }

  private void ensureTimestamp(ObjectNode node, MessageProperties properties) {
    if (node.hasNonNull("timestamp")) {
      return;
    }
    Instant timestamp = null;
    if (properties.getTimestamp() != null) {
      timestamp = properties.getTimestamp().toInstant();
    } else {
      String createdAt = textOrNull(node.get("createdAt"));
      if (createdAt != null && !createdAt.isBlank()) {
        node.put("timestamp", createdAt);
        return;
      }
    }
    if (timestamp == null) {
      timestamp = Instant.now();
    }
    node.put("timestamp", timestamp.toString());
  }

  private void putIfMissing(ObjectNode node, String field, String value) {
    if (!node.hasNonNull(field) && value != null) {
      node.put(field, value);
    }
  }

  private String textOrNull(JsonNode node) {
    if (node == null || node.isNull()) {
      return null;
    }
    String text = node.asText();
    return text != null && text.isBlank() ? null : text;
  }

  private void updateListenerState(boolean start) {
    MessageListenerContainer container = registry.getListenerContainer("workListener");
    if (container == null) {
      return;
    }
    if (start) {
      container.start();
    } else {
      container.stop();
    }
  }
}
