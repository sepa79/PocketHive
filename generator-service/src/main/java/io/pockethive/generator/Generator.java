package io.pockethive.generator;

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
import io.pockethive.controlplane.worker.WorkerStatusRequest;
import io.pockethive.observability.ObservabilityContext;
import io.pockethive.observability.ObservabilityContextUtil;
import io.pockethive.worker.runtime.AbstractWorkerRuntime;
import io.pockethive.worker.runtime.WorkerMessageEnvelope;
import io.pockethive.worker.runtime.WorkerMessageEnvelopeCodec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

@Component
@EnableScheduling
public class Generator extends AbstractWorkerRuntime {

  private static final Logger log = LoggerFactory.getLogger(Generator.class);
  private static final String ROLE = "generator";
  private static final TypeReference<Map<String, String>> MAP_STRING_STRING = new TypeReference<>() {};
  private static final String CONFIG_PHASE = "apply";
  private static final long STATUS_INTERVAL_MS = 5000L;

  private final RabbitTemplate rabbit;
  private final MessageConfig messageConfig;
  private final ObjectMapper objectMapper;
  private final WorkerMessageEnvelopeCodec envelopeCodec;
  private final AtomicLong counter = new AtomicLong();
  private volatile boolean enabled = false;
  private volatile long lastStatusTs = System.currentTimeMillis();

  public Generator(RabbitTemplate rabbit,
                   @Qualifier("generatorControlPlaneEmitter") ControlPlaneEmitter controlEmitter,
                   @Qualifier("workerControlPlaneIdentity") ControlPlaneIdentity identity,
                   @Qualifier("workerControlPlaneTopologyDescriptor") ControlPlaneTopologyDescriptor topology,
                   WorkerControlPlane controlPlane,
                   MessageConfig messageConfig,
                   ObjectMapper objectMapper) {
    super(log, controlEmitter, controlPlane, identity, topology);
    this.rabbit = Objects.requireNonNull(rabbit, "rabbit");
    this.messageConfig = Objects.requireNonNull(messageConfig, "messageConfig");
    this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    this.envelopeCodec = new WorkerMessageEnvelopeCodec(objectMapper);
    // Emit full snapshot on startup
    try { sendStatusFull(0); } catch (Exception ignore) { }
  }

  @Value("${pockethive.generator.rate-per-sec:${ph.gen.ratePerSec:0}}")
  private volatile double ratePerSec;

  private double carryOver = 0;

  @Scheduled(fixedRate = 1000)
  public void tick() {
    if(!enabled) return;
    double planned = ratePerSec + carryOver;
    int whole = (int) Math.floor(planned);
    carryOver = planned - whole;
    for (int i = 0; i < whole; i++) {
      sendOnce();
    }
  }

  @Scheduled(fixedRate = STATUS_INTERVAL_MS)
  public void status() {
    long now = System.currentTimeMillis();
    long elapsed = now - lastStatusTs;
    lastStatusTs = now;
    long tps = elapsed > 0 ? counter.getAndSet(0) * 1000 / elapsed : 0;
    sendStatusDelta(tps);
  }

  @RabbitListener(queues = "#{@generatorControlQueueName}")
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
      boolean handled = controlPlane().consume(payload, rk, controlListener());
      if (!handled) {
        log.debug("Ignoring control payload on routing key {}", rk);
      }
    } finally {
      MDC.clear();
    }
  }

  @Override
  protected void handleStatusRequest(WorkerStatusRequest request) {
    sendStatusFull(0);
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

  private void applyConfig(Map<String, Object> data) {
    if (data == null || data.isEmpty()) {
      return;
    }
    if (data.containsKey("ratePerSec")) {
      ratePerSec = parseDouble(data.get("ratePerSec"), "ratePerSec", ratePerSec);
    }
    if (data.containsKey("enabled")) {
      enabled = parseBoolean(data.get("enabled"), "enabled", enabled);
    }
    if (data.containsKey("path")) {
      messageConfig.setPath(asString(data.get("path"), "path", messageConfig.getPath()));
    }
    if (data.containsKey("method")) {
      messageConfig.setMethod(asString(data.get("method"), "method", messageConfig.getMethod()));
    }
    if (data.containsKey("body")) {
      messageConfig.setBody(asString(data.get("body"), "body", messageConfig.getBody()));
    }
    if (data.containsKey("headers")) {
      Map<String, String> headers = parseHeaders(data.get("headers"));
      if (headers != null) {
        messageConfig.setHeaders(headers);
      }
    }
    if (data.containsKey("singleRequest") && parseBoolean(data.get("singleRequest"), "singleRequest", false)) {
      sendOnce();
    }
  }

  private double parseDouble(Object value, String field, double current) {
    if (value == null) {
      return current;
    }
    if (value instanceof Number number) {
      return number.doubleValue();
    }
    if (value instanceof String s) {
      if (s.isBlank()) {
        return current;
      }
      try {
        return Double.parseDouble(s);
      } catch (NumberFormatException ex) {
        throw new IllegalArgumentException("Invalid %s value: %s".formatted(field, s), ex);
      }
    }
    throw new IllegalArgumentException("Invalid %s value type: %s".formatted(field, value.getClass().getSimpleName()));
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
      return Boolean.parseBoolean(s);
    }
    throw new IllegalArgumentException("Invalid %s value type: %s".formatted(field, value.getClass().getSimpleName()));
  }

  private String asString(Object value, String field, String current) {
    if (value == null) {
      return current;
    }
    if (value instanceof String s) {
      return s;
    }
    throw new IllegalArgumentException("Invalid %s value type: %s".formatted(field, value.getClass().getSimpleName()));
  }

  private Map<String, String> parseHeaders(Object value) {
    if (value == null) {
      return null;
    }
    if (!(value instanceof Map<?, ?> map)) {
      throw new IllegalArgumentException("Invalid headers value type: " + value.getClass().getSimpleName());
    }
    try {
      return objectMapper.convertValue(map, MAP_STRING_STRING);
    } catch (IllegalArgumentException ex) {
      throw new IllegalArgumentException("Invalid headers value", ex);
    }
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

  private void sendOnce(){
    String id = UUID.randomUUID().toString();
    Map<String,Object> payload = new LinkedHashMap<>();
    payload.put("id", id);
    payload.put("path", messageConfig.getPath());
    payload.put("method", messageConfig.getMethod());
    payload.put("headers", messageConfig.getHeaders());
    payload.put("body", messageConfig.getBody());
    payload.put("createdAt", Instant.now().toString());
    Instant timestamp = Instant.now();
    ObjectNode payloadNode = objectMapper.valueToTree(payload);
    WorkerMessageEnvelope envelope = new WorkerMessageEnvelope(
        id,
        timestamp,
        null,
        null,
        null,
        identity().role(),
        identity().instanceId(),
        identity().swarmId(),
        null,
        null,
        null,
        null,
        payloadNode);
    ObjectNode encoded = envelopeCodec.encodeToJson(envelope);
    encoded.remove(List.of("messageId", "timestamp", "role", "instance", "swarmId"));
    String body;
    try {
      body = objectMapper.writeValueAsString(encoded);
    } catch (Exception e) {
      body = "{}";
    }
    ObservabilityContext ctx = ObservabilityContextUtil.init(ROLE, identity().instanceId());
    Instant now = Instant.now();
    ObservabilityContextUtil.appendHop(ctx, ROLE, identity().instanceId(), now, now);
    Message msg = MessageBuilder
        .withBody(body.getBytes(StandardCharsets.UTF_8))
        .setContentType(MessageProperties.CONTENT_TYPE_JSON)
        .setContentEncoding(StandardCharsets.UTF_8.name())
        .setMessageId(id)
        .setHeader("x-ph-service", ROLE)
        .setHeader(ObservabilityContextUtil.HEADER, ObservabilityContextUtil.toHeader(ctx))
        .build();
    rabbit.convertAndSend(Topology.EXCHANGE, Topology.GEN_QUEUE, msg);
    counter.incrementAndGet();
  }
  @Override
  protected ControlPlaneEmitter.StatusContext statusContext(long tps) {
    return baseStatusContext(tps, builder -> builder
        .traffic(Topology.EXCHANGE)
        .workOut(Topology.GEN_QUEUE)
        .enabled(enabled)
        .data("ratePerSec", ratePerSec)
        .data("path", messageConfig.getPath())
        .data("method", messageConfig.getMethod())
        .data("body", messageConfig.getBody())
        .data("headers", messageConfig.getHeaders()));
  }

  @Override
  protected String statusLogDetails(long tps) {
    return super.statusLogDetails(tps) + " enabled=" + enabled;
  }

  private CommandState currentState(String status) {
    return new CommandState(status, enabled, stateDetails());
  }

  private Map<String, Object> stateDetails() {
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("ratePerSec", ratePerSec);
    details.put("path", messageConfig.getPath());
    details.put("method", messageConfig.getMethod());
    details.put("body", messageConfig.getBody());
    details.put("headers", messageConfig.getHeaders());
    return details;
  }
}
