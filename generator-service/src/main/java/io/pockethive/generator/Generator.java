package io.pockethive.generator;

import io.pockethive.Topology;
import io.pockethive.control.ControlSignal;
import io.pockethive.observability.ObservabilityContext;
import io.pockethive.observability.ObservabilityContextUtil;
import io.pockethive.observability.StatusEnvelopeBuilder;
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

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

@Component
@EnableScheduling
public class Generator {

  private static final Logger log = LoggerFactory.getLogger(Generator.class);
  private static final String ROLE = "generator";
  private static final TypeReference<Map<String, Object>> MAP_STRING_OBJECT = new TypeReference<>() {};
  private static final TypeReference<Map<String, String>> MAP_STRING_STRING = new TypeReference<>() {};
  private final RabbitTemplate rabbit;
  private final AtomicLong counter = new AtomicLong();
  private final String instanceId;
  private final MessageConfig messageConfig;
  private final ObjectMapper objectMapper;
  private volatile boolean enabled = false;
  private static final long STATUS_INTERVAL_MS = 5000L;
  private volatile long lastStatusTs = System.currentTimeMillis();

  public Generator(RabbitTemplate rabbit,
                   @Qualifier("instanceId") String instanceId,
                   MessageConfig messageConfig,
                   ObjectMapper objectMapper) {
    this.rabbit = rabbit;
    this.instanceId = instanceId;
    this.messageConfig = messageConfig;
    this.objectMapper = objectMapper;
    // Emit full snapshot on startup
    try{ sendStatusFull(0); } catch(Exception ignore){}
  }

  @Value("${ph.gen.ratePerSec:0}")
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

  @RabbitListener(queues = "#{@controlQueue.name}")
  public void onControl(String payload,
                        @Header(AmqpHeaders.RECEIVED_ROUTING_KEY) String rk,
                        @Header(value = ObservabilityContextUtil.HEADER, required = false) String trace) {
    ObservabilityContext ctx = ObservabilityContextUtil.fromHeader(trace);
    ObservabilityContextUtil.populateMdc(ctx);
    try {
      String p = payload==null?"" : (payload.length()>300? payload.substring(0,300)+"…" : payload);
      log.info("[CTRL] RECV rk={} inst={} payload={}", rk, instanceId, p);
      if(payload==null || payload.isBlank()){
        return;
      }
      ControlSignal cs;
      try {
        cs = objectMapper.readValue(payload, ControlSignal.class);
      } catch (Exception e) {
        log.warn("control parse", e);
        return;
      }
      if (cs.correlationId() != null) {
        MDC.put("correlation_id", cs.correlationId());
      }
      if (cs.idempotencyKey() != null) {
        MDC.put("idempotency_key", cs.idempotencyKey());
      }
      String signal = cs.signal();
      if (signal == null) {
        log.warn("control missing signal");
        return;
      }
      switch (signal) {
        case "status-request" -> sendStatusFull(0);
        case "config-update" -> handleConfigUpdate(cs);
        default -> log.debug("Ignoring unsupported control signal {}", signal);
      }
    } finally {
      MDC.clear();
    }
  }

  private void handleConfigUpdate(ControlSignal cs) {
    try {
      Map<String, Object> data = extractConfigData(cs);
      applyConfig(data);
      emitConfigSuccess(cs);
    } catch (Exception e) {
      log.warn("config update", e);
      emitConfigError(cs, e);
    }
  }

  private Map<String, Object> extractConfigData(ControlSignal cs) {
    Map<String, Object> args = cs.args();
    if (args == null || args.isEmpty()) {
      return null;
    }
    Object data = args.get("data");
    if (data == null) {
      return objectMapper.convertValue(args, MAP_STRING_OBJECT);
    }
    return objectMapper.convertValue(data, MAP_STRING_OBJECT);
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
    if (data.containsKey("singleRequest") && parseBoolean(data.get("singleRequest"), "singleRequest", false)) {
      sendOnce();
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
    String role = resolveRole(cs);
    String instance = resolveInstance(cs);
    String routingKey = "ev.ready.config-update." + role + "." + instance;
    ObjectNode payload = confirmationPayload(cs, "success", role, instance);
    rabbit.convertAndSend(Topology.CONTROL_EXCHANGE, routingKey, payload.toString());
  }

  private void emitConfigError(ControlSignal cs, Exception e) {
    String role = resolveRole(cs);
    String instance = resolveInstance(cs);
    String routingKey = "ev.error.config-update." + role + "." + instance;
    ObjectNode payload = confirmationPayload(cs, "error", role, instance);
    payload.put("code", e.getClass().getSimpleName());
    if (e.getMessage() != null) {
      payload.put("message", e.getMessage());
    }
    rabbit.convertAndSend(Topology.CONTROL_EXCHANGE, routingKey, payload.toString());
  }

  private ObjectNode confirmationPayload(ControlSignal cs, String result, String role, String instance) {
    ObjectNode payload = objectMapper.createObjectNode();
    payload.put("signal", cs.signal());
    payload.put("result", result);
    payload.set("scope", scopeNode(cs, role, instance));
    if (cs.args() != null) {
      payload.set("args", objectMapper.valueToTree(cs.args()));
    }
    if (cs.correlationId() != null) {
      payload.put("correlationId", cs.correlationId());
    }
    if (cs.idempotencyKey() != null) {
      payload.put("idempotencyKey", cs.idempotencyKey());
    }
    return payload;
  }

  private ObjectNode scopeNode(ControlSignal cs, String role, String instance) {
    ObjectNode scope = objectMapper.createObjectNode();
    String swarm = resolveSwarm(cs);
    if (swarm != null && !swarm.isBlank()) {
      scope.put("swarmId", swarm);
    }
    if (role != null && !role.isBlank()) {
      scope.put("role", role);
    }
    if (instance != null && !instance.isBlank()) {
      scope.put("instance", instance);
    }
    return scope;
  }

  private String resolveSwarm(ControlSignal cs) {
    if (cs.swarmId() != null && !cs.swarmId().isBlank()) {
      return cs.swarmId();
    }
    return Topology.SWARM_ID;
  }

  private String resolveRole(ControlSignal cs) {
    if (cs.role() != null && !cs.role().isBlank()) {
      return cs.role();
    }
    return ROLE;
  }

  private String resolveInstance(ControlSignal cs) {
    if (cs.instance() != null && !cs.instance().isBlank()) {
      return cs.instance();
    }
    return instanceId;
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
    String body;
    try{
      body = objectMapper.writeValueAsString(payload);
    }catch(Exception e){
      body = "{}";
    }
    ObservabilityContext ctx = ObservabilityContextUtil.init(ROLE, instanceId);
    Instant now = Instant.now();
    ObservabilityContextUtil.appendHop(ctx, ROLE, instanceId, now, now);
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



  private void sendStatusDelta(long tps){
    String controlQueue = Topology.CONTROL_QUEUE + "." + ROLE + "." + instanceId;
    String routingKey = "ev.status-delta." + ROLE + "." + instanceId;
    String json = new StatusEnvelopeBuilder()
        .kind("status-delta")
        .role(ROLE)
        .instance(instanceId)
        .traffic(Topology.EXCHANGE)
        .controlIn(controlQueue)
        .controlRoutes(
            "sig.config-update",
            "sig.config-update."+ROLE,
            "sig.config-update."+ROLE+"."+instanceId,
            "sig.status-request",
            "sig.status-request."+ROLE,
            "sig.status-request."+ROLE+"."+instanceId
        )
        .controlOut(routingKey)
        .workOut(Topology.GEN_QUEUE)
        .tps(tps)
        .enabled(enabled)
        .data("ratePerSec", ratePerSec)
        .data("path", messageConfig.getPath())
        .data("method", messageConfig.getMethod())
        .data("body", messageConfig.getBody())
        .data("headers", messageConfig.getHeaders())
        .toJson();
    rabbit.convertAndSend(Topology.CONTROL_EXCHANGE, routingKey, json);
  }

  private void sendStatusFull(long tps){
    String controlQueue = Topology.CONTROL_QUEUE + "." + ROLE + "." + instanceId;
    String routingKey = "ev.status-full." + ROLE + "." + instanceId;
    String json = new StatusEnvelopeBuilder()
        .kind("status-full")
        .role(ROLE)
        .instance(instanceId)
        .traffic(Topology.EXCHANGE)
        .controlIn(controlQueue)
        .controlRoutes(
            "sig.config-update",
            "sig.config-update."+ROLE,
            "sig.config-update."+ROLE+"."+instanceId,
            "sig.status-request",
            "sig.status-request."+ROLE,
            "sig.status-request."+ROLE+"."+instanceId
        )
        .controlOut(routingKey)
        .workOut(Topology.GEN_QUEUE)
        .tps(tps)
        .enabled(enabled)
        .data("ratePerSec", ratePerSec)
        .data("path", messageConfig.getPath())
        .data("method", messageConfig.getMethod())
        .data("body", messageConfig.getBody())
        .data("headers", messageConfig.getHeaders())
        .toJson();
    rabbit.convertAndSend(Topology.CONTROL_EXCHANGE, routingKey, json);
  }
}
