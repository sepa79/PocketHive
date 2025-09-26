package io.pockethive.trigger;

import io.pockethive.Topology;
import io.pockethive.control.ControlSignal;
import io.pockethive.control.ConfirmationScope;
import io.pockethive.controlplane.ControlPlaneIdentity;
import io.pockethive.controlplane.worker.WorkerConfigCommand;
import io.pockethive.controlplane.worker.WorkerControlPlane;
import io.pockethive.controlplane.worker.WorkerSignalListener;
import io.pockethive.controlplane.worker.WorkerSignalListener.WorkerSignalContext;
import io.pockethive.controlplane.worker.WorkerStatusRequest;
import io.pockethive.controlplane.routing.ControlPlaneRouting;
import io.pockethive.observability.ObservabilityContext;
import io.pockethive.observability.ObservabilityContextUtil;
import io.pockethive.observability.StatusEnvelopeBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

@Component
@EnableScheduling
public class Trigger {
  private static final Logger log = LoggerFactory.getLogger(Trigger.class);
  private static final String ROLE = "trigger";
  private static final TypeReference<Map<String, String>> MAP_STRING_STRING = new TypeReference<>() {};
  private static final long STATUS_INTERVAL_MS = 5000L;

  private final RabbitTemplate rabbit;
  private final String instanceId;
  private final TriggerConfig config;
  private final ObjectMapper objectMapper;
  private volatile boolean enabled = false;
  private volatile long lastRun = 0L;
  private final AtomicLong counter = new AtomicLong();
  private volatile long lastStatusTs = System.currentTimeMillis();
  private final WorkerControlPlane controlPlane;
  private final WorkerSignalListener controlListener;

  public Trigger(RabbitTemplate rabbit,
                 @Qualifier("instanceId") String instanceId,
                 TriggerConfig config,
                 ObjectMapper objectMapper) {
    this.rabbit = rabbit;
    this.instanceId = instanceId;
    this.config = config;
    this.objectMapper = objectMapper;
    this.controlPlane = WorkerControlPlane.builder(objectMapper)
        .identity(new ControlPlaneIdentity(Topology.SWARM_ID, ROLE, instanceId))
        .build();
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
    try { sendStatusFull(0); } catch (Exception ignore) {}
  }

  @Scheduled(fixedRate = 1000)
  public void tick() {
    if (!enabled) return;
    long now = System.currentTimeMillis();
    if (now - lastRun >= config.getIntervalMs()) {
      lastRun = now;
      triggerOnce();
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
      if (payload == null || payload.isBlank()) {
        return;
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
    if (data.containsKey("intervalMs")) {
      config.setIntervalMs(parseLong(data.get("intervalMs"), "intervalMs", config.getIntervalMs()));
    }
    if (data.containsKey("enabled")) {
      enabled = parseBoolean(data.get("enabled"), "enabled", enabled);
    }
    if (data.containsKey("singleRequest") && parseBoolean(data.get("singleRequest"), "singleRequest", false)) {
      triggerOnce();
    }
    if (data.containsKey("actionType")) {
      config.setActionType(asString(data.get("actionType"), "actionType", config.getActionType()));
    }
    if (data.containsKey("command")) {
      config.setCommand(asString(data.get("command"), "command", config.getCommand()));
    }
    if (data.containsKey("url")) {
      config.setUrl(asString(data.get("url"), "url", config.getUrl()));
    }
    if (data.containsKey("method")) {
      config.setMethod(asString(data.get("method"), "method", config.getMethod()));
    }
    if (data.containsKey("body")) {
      config.setBody(asString(data.get("body"), "body", config.getBody()));
    }
    if (data.containsKey("headers")) {
      Map<String, String> headers = parseHeaders(data.get("headers"));
      if (headers != null) {
        config.setHeaders(headers);
      }
    }
  }

  private long parseLong(Object value, String field, long current) {
    if (value == null) {
      return current;
    }
    if (value instanceof Number number) {
      return number.longValue();
    }
    if (value instanceof String s) {
      if (s.isBlank()) {
        return current;
      }
      try {
        return Long.parseLong(s);
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
      if ("true".equalsIgnoreCase(s) || "false".equalsIgnoreCase(s)) {
        return Boolean.parseBoolean(s);
      }
    }
    throw new IllegalArgumentException("Invalid %s value".formatted(field));
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
    String swarm = resolveSwarm(cs);
    String role = resolveRole(cs);
    String instance = resolveInstance(cs);
    String rk = ControlPlaneRouting.event("ready.config-update",
        new ConfirmationScope(swarm, role, instance));
    ObjectNode payload = confirmationPayload(cs, "success", role, instance);
    String json = payload.toString();
    logControlSend(rk, json);
    rabbit.convertAndSend(Topology.CONTROL_EXCHANGE, rk, json);
  }

  private void emitConfigError(ControlSignal cs, Exception e) {
    String swarm = resolveSwarm(cs);
    String role = resolveRole(cs);
    String instance = resolveInstance(cs);
    String rk = ControlPlaneRouting.event("error.config-update",
        new ConfirmationScope(swarm, role, instance));
    ObjectNode payload = confirmationPayload(cs, "error", role, instance);
    payload.put("code", e.getClass().getSimpleName());
    String message = e.getMessage();
    if (message == null || message.isBlank()) {
      message = e.getClass().getSimpleName();
    }
    payload.put("message", message);
    String json = payload.toString();
    logControlSend(rk, json);
    rabbit.convertAndSend(Topology.CONTROL_EXCHANGE, rk, json);
  }

  private ObjectNode confirmationPayload(ControlSignal cs, String result, String role, String instance) {
    ObjectNode payload = objectMapper.createObjectNode();
    payload.put("ts", Instant.now().toString());
    payload.put("signal", cs.signal());
    payload.put("result", result);
    payload.set("scope", scopeNode(cs, role, instance));
    payload.set("state", stateNode(cs, role, instance));
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

  private ObjectNode stateNode(ControlSignal cs, String role, String instance) {
    ObjectNode state = objectMapper.createObjectNode();
    state.put("enabled", enabled);
    return state;
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

  private void triggerOnce() {
    counter.incrementAndGet();
    if ("shell".equalsIgnoreCase(config.getActionType())) {
      runShell();
    } else if ("rest".equalsIgnoreCase(config.getActionType())) {
      callRest();
    } else {
      log.warn("Unknown actionType: {}", config.getActionType());
    }
  }

  private void runShell() {
    try {
      Process p = new ProcessBuilder("bash", "-c", config.getCommand()).start();
      try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
        String line;
        while ((line = r.readLine()) != null) {
          log.debug("[SHELL] {}", line);
        }
      }
      int exit = p.waitFor();
      log.debug("[SHELL] exit={} cmd={}", exit, config.getCommand());
    } catch (Exception e) {
      log.warn("shell exec", e);
    }
  }

  private void callRest() {
    try {
      HttpRequest.Builder b = HttpRequest.newBuilder().uri(URI.create(config.getUrl()));
      String m = config.getMethod() == null ? "GET" : config.getMethod().toUpperCase();
      log.debug("[REST] REQ {} {} headers={} body={}", m, config.getUrl(), config.getHeaders(), snippet(config.getBody()));
      if (config.getBody() != null && !config.getBody().isEmpty()) {
        b.method(m, HttpRequest.BodyPublishers.ofString(config.getBody()));
      } else {
        b.method(m, HttpRequest.BodyPublishers.noBody());
      }
      for (Map.Entry<String, String> e : config.getHeaders().entrySet()) {
        b.header(e.getKey(), e.getValue());
      }
      HttpResponse<String> response = HttpClient.newHttpClient().send(b.build(), HttpResponse.BodyHandlers.ofString());
      log.debug("[REST] RESP {} {} status={} headers={} body={}",
          m,
          config.getUrl(),
          response.statusCode(),
          response.headers().map(),
          snippet(response.body()));
    } catch (Exception e) {
      log.warn("rest call", e);
    }
  }

  private void sendStatusDelta(long tps) {
    String controlQueue = controlQueueName();
    String routingKey = ControlPlaneRouting.event("status-delta",
        new ConfirmationScope(Topology.SWARM_ID, ROLE, instanceId));
    String json = new StatusEnvelopeBuilder()
        .kind("status-delta")
        .role(ROLE)
        .instance(instanceId)
        .swarmId(Topology.SWARM_ID)
        .traffic(Topology.EXCHANGE)
        .controlIn(controlQueue)
        .controlRoutes(controlRoutes())
        .controlOut(routingKey)
        .tps(tps)
        .enabled(enabled)
        .data("intervalMs", config.getIntervalMs())
        .data("actionType", config.getActionType())
        .data("command", config.getCommand())
        .data("url", config.getUrl())
        .data("method", config.getMethod())
        .data("body", config.getBody())
        .data("headers", config.getHeaders())
        .toJson();
    logControlSend(routingKey, json);
    rabbit.convertAndSend(Topology.CONTROL_EXCHANGE, routingKey, json);
  }

  private void sendStatusFull(long tps) {
    String controlQueue = controlQueueName();
    String routingKey = ControlPlaneRouting.event("status-full",
        new ConfirmationScope(Topology.SWARM_ID, ROLE, instanceId));
    String json = new StatusEnvelopeBuilder()
        .kind("status-full")
        .role(ROLE)
        .instance(instanceId)
        .swarmId(Topology.SWARM_ID)
        .traffic(Topology.EXCHANGE)
        .controlIn(controlQueue)
        .controlRoutes(controlRoutes())
        .controlOut(routingKey)
        .tps(tps)
        .enabled(enabled)
        .data("intervalMs", config.getIntervalMs())
        .data("actionType", config.getActionType())
        .data("command", config.getCommand())
        .data("url", config.getUrl())
        .data("method", config.getMethod())
        .data("body", config.getBody())
        .data("headers", config.getHeaders())
        .toJson();
    logControlSend(routingKey, json);
    rabbit.convertAndSend(Topology.CONTROL_EXCHANGE, routingKey, json);
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

  private String controlQueueName() {
    return Topology.CONTROL_QUEUE + "." + Topology.SWARM_ID + "." + ROLE + "." + instanceId;
  }

  private String[] controlRoutes() {
    return new String[] {
        ControlPlaneRouting.signal("config-update", "ALL", ROLE, "ALL"),
        ControlPlaneRouting.signal("config-update", Topology.SWARM_ID, ROLE, "ALL"),
        ControlPlaneRouting.signal("config-update", Topology.SWARM_ID, ROLE, instanceId),
        ControlPlaneRouting.signal("config-update", Topology.SWARM_ID, "ALL", "ALL"),
        ControlPlaneRouting.signal("status-request", "ALL", ROLE, "ALL"),
        ControlPlaneRouting.signal("status-request", Topology.SWARM_ID, ROLE, "ALL"),
        ControlPlaneRouting.signal("status-request", Topology.SWARM_ID, ROLE, instanceId)
    };
  }

  private void logControlSend(String routingKey, String payload) {
    String snippet = snippet(payload);
    if (routingKey.contains(".status-")) {
      log.debug("[CTRL] SEND rk={} inst={} payload={}", routingKey, instanceId, snippet);
    } else if (routingKey.contains(".config-update.")) {
      log.info("[CTRL] SEND rk={} inst={} payload={}", routingKey, instanceId, snippet);
    } else {
      log.info("[CTRL] SEND rk={} inst={} payload={}", routingKey, instanceId, snippet);
    }
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
