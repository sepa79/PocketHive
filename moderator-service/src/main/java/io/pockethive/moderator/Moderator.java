package io.pockethive.moderator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

@Component
@EnableScheduling
public class Moderator {

  private static final Logger log = LoggerFactory.getLogger(Moderator.class);
  private static final String ROLE = "moderator";
  private final RabbitTemplate rabbit;
  private final AtomicLong counter = new AtomicLong();
  private final String instanceId;
  private final RabbitListenerEndpointRegistry registry;
  private final ObjectMapper objectMapper;
  private volatile boolean enabled = false;
  private static final long STATUS_INTERVAL_MS = 5000L;
  private volatile long lastStatusTs = System.currentTimeMillis();

  public Moderator(RabbitTemplate rabbit,
                   @Qualifier("instanceId") String instanceId,
                   RabbitListenerEndpointRegistry registry,
                   ObjectMapper objectMapper) {
    this.rabbit = rabbit;
    this.instanceId = instanceId;
    this.registry = registry;
    this.objectMapper = objectMapper;
    try{ sendStatusFull(0); } catch(Exception ignore){}
  }

  // Consume RAW AMQP message to avoid converter issues
  @RabbitListener(id = "workListener", queues = "${ph.genQueue:ph.default.gen}")
  public void onGenerated(Message message,
                          @Header(value = "x-ph-service", required = false) String service,
                          @Header(value = ObservabilityContextUtil.HEADER, required = false) String trace) {
    Instant received = Instant.now();
    ObservabilityContext ctx = ObservabilityContextUtil.fromHeader(trace);
    if(ctx==null){
      ctx = ObservabilityContextUtil.init("moderator", instanceId);
      ctx.getHops().clear();
    }
    ObservabilityContextUtil.populateMdc(ctx);
    try {
      if(enabled){
        counter.incrementAndGet();
        Instant processed = Instant.now();
        ObservabilityContextUtil.appendHop(ctx, "moderator", instanceId, received, processed);
        Message out = MessageBuilder.fromMessage(message)
            .setHeader("x-ph-service", "moderator")
            .setHeader(ObservabilityContextUtil.HEADER, ObservabilityContextUtil.toHeader(ctx))
            .build();
        rabbit.send(Topology.EXCHANGE, Topology.MOD_QUEUE, out);
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
      if (signal == null || signal.isBlank()) {
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
      Boolean desired = extractEnabled(cs.args());
      applyEnabled(desired);
      emitConfigSuccess(cs);
    } catch (Exception e) {
      log.warn("config update", e);
      emitConfigError(cs, e);
    }
  }

  private Boolean extractEnabled(Map<String, Object> args) {
    if (args == null || args.isEmpty()) {
      return null;
    }
    if (args.containsKey("enabled")) {
      return parseEnabled(args.get("enabled"));
    }
    Object data = args.get("data");
    if (data instanceof Map<?, ?> map && map.containsKey("enabled")) {
      return parseEnabled(map.get("enabled"));
    }
    return null;
  }

  private Boolean parseEnabled(Object value) {
    if (value == null) {
      return null;
    }
    if (value instanceof Boolean b) {
      return b;
    }
    if (value instanceof String s) {
      if (s.isBlank()) {
        return null;
      }
      if ("true".equalsIgnoreCase(s) || "false".equalsIgnoreCase(s)) {
        return Boolean.parseBoolean(s);
      }
      throw new IllegalArgumentException("Invalid enabled value: " + s);
    }
    throw new IllegalArgumentException("Invalid enabled value type: " + value.getClass().getSimpleName());
  }

  private void applyEnabled(Boolean desired) {
    if (desired == null) {
      return;
    }
    boolean changed = desired != enabled;
    enabled = desired;
    if (!changed) {
      return;
    }
    MessageListenerContainer c = registry.getListenerContainer("workListener");
    if (c != null) {
      if (desired) {
        c.start();
      } else {
        c.stop();
      }
    }
  }

  private void emitConfigSuccess(ControlSignal cs) {
    String role = resolveRole(cs);
    String instance = resolveInstance(cs);
    String routingKey = "ev.ready.config-update." + role + "." + instance;
    ObjectNode payload = confirmationPayload(cs, "success", role, instance);
    String json = payload.toString();
    logControlSend(routingKey, json);
    rabbit.convertAndSend(Topology.CONTROL_EXCHANGE, routingKey, json);
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
    String json = payload.toString();
    logControlSend(routingKey, json);
    rabbit.convertAndSend(Topology.CONTROL_EXCHANGE, routingKey, json);
  }

  private ObjectNode confirmationPayload(ControlSignal cs, String result, String role, String instance) {
    ObjectNode payload = objectMapper.createObjectNode();
    payload.put("signal", cs.signal());
    payload.put("result", result);
    payload.set("scope", scopeNode(cs, role, instance));
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



  private void sendStatusDelta(long tps){
    String role = ROLE;
    String controlQueue = Topology.CONTROL_QUEUE + "." + role + "." + instanceId;
    String rk = "ev.status-delta."+role+"."+instanceId;
    String payload = new StatusEnvelopeBuilder()
        .kind("status-delta")
        .role(role)
        .instance(instanceId)
        .traffic(Topology.EXCHANGE)
        .workIn(Topology.GEN_QUEUE)
        .workRoutes(Topology.GEN_QUEUE)
        .workOut(Topology.MOD_QUEUE)
        .controlIn(controlQueue)
        .controlRoutes(
            "sig.config-update",
            "sig.config-update."+role,
            "sig.config-update."+role+"."+instanceId,
            "sig.status-request",
            "sig.status-request."+role,
            "sig.status-request."+role+"."+instanceId
        )
        .controlOut(rk)
        .tps(tps)
        .enabled(enabled)
        .toJson();
    logControlSend(rk, payload);
    rabbit.convertAndSend(Topology.CONTROL_EXCHANGE, rk, payload);
  }
  private void sendStatusFull(long tps){
    String role = ROLE;
    String controlQueue = Topology.CONTROL_QUEUE + "." + role + "." + instanceId;
    String rk = "ev.status-full."+role+"."+instanceId;
    String payload = new StatusEnvelopeBuilder()
        .kind("status-full")
        .role(role)
        .instance(instanceId)
        .traffic(Topology.EXCHANGE)
        .workIn(Topology.GEN_QUEUE)
        .workRoutes(Topology.GEN_QUEUE)
        .workOut(Topology.MOD_QUEUE)
        .controlIn(controlQueue)
        .controlRoutes(
            "sig.config-update",
            "sig.config-update."+role,
            "sig.config-update."+role+"."+instanceId,
            "sig.status-request",
            "sig.status-request."+role,
            "sig.status-request."+role+"."+instanceId
        )
        .controlOut(rk)
        .tps(tps)
        .enabled(enabled)
        .toJson();
    logControlSend(rk, payload);
    rabbit.convertAndSend(Topology.CONTROL_EXCHANGE, rk, payload);
  }

  private void logControlSend(String routingKey, String payload) {
    log.info("[CTRL] SEND rk={} inst={} payload={}", routingKey, instanceId, snippet(payload));
  }

  private static String snippet(String payload) {
    if (payload == null) {
      return "";
    }
    String trimmed = payload.strip();
    if (trimmed.length() > 300) {
      return trimmed.substring(0, 300) + "…";
    }
    return trimmed;
  }
}
