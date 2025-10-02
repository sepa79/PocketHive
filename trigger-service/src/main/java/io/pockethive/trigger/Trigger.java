package io.pockethive.trigger;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.Topology;
import io.pockethive.control.CommandState;
import io.pockethive.control.ControlSignal;
import io.pockethive.controlplane.ControlPlaneIdentity;
import io.pockethive.controlplane.messaging.ControlPlaneEmitter;
import io.pockethive.controlplane.routing.ControlPlaneRouting;
import io.pockethive.controlplane.topology.ControlPlaneTopologyDescriptor;
import io.pockethive.controlplane.worker.WorkerConfigCommand;
import io.pockethive.controlplane.worker.WorkerControlPlane;
import io.pockethive.observability.ObservabilityContext;
import io.pockethive.observability.ObservabilityContextUtil;
import io.pockethive.worker.runtime.AbstractWorkerRuntime;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
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

@Component
@EnableScheduling
public class Trigger extends AbstractWorkerRuntime {

  private static final Logger log = LoggerFactory.getLogger(Trigger.class);
  private static final String ROLE = "trigger";
  private static final long STATUS_INTERVAL_MS = 5000L;
  private static final String CONFIG_PHASE = "apply";
  private static final TypeReference<Map<String, String>> MAP_STRING_STRING = new TypeReference<>() { };

  private final RabbitTemplate rabbit;
  private final TriggerConfig config;
  private final ObjectMapper objectMapper;

  private final AtomicLong counter = new AtomicLong();
  private volatile boolean enabled;
  private volatile long lastRun;
  private volatile long lastStatusTs = System.currentTimeMillis();

  public Trigger(RabbitTemplate rabbit,
                 @Qualifier("triggerControlPlaneEmitter") ControlPlaneEmitter controlEmitter,
                 @Qualifier("workerControlPlaneIdentity") ControlPlaneIdentity identity,
                 @Qualifier("workerControlPlaneTopologyDescriptor") ControlPlaneTopologyDescriptor topology,
                 WorkerControlPlane controlPlane,
                 TriggerConfig config,
                 ObjectMapper objectMapper) {
    super(log, controlEmitter, controlPlane, identity, topology);
    this.rabbit = Objects.requireNonNull(rabbit, "rabbit");
    this.config = Objects.requireNonNull(config, "config");
    this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    try {
      sendStatusFull(0);
    } catch (Exception ignore) {
      // best-effort during startup
    }
  }

  @Scheduled(fixedRate = 1000)
  public void tick() {
    if (!enabled) {
      return;
    }
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

  @RabbitListener(queues = "#{@triggerControlQueueName}")
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
    ControlSignal signal = command.signal();
    try {
      applyConfig(command.data());
      emitConfigSuccess(signal);
    } catch (Exception e) {
      log.warn("config update", e);
      emitConfigError(signal, e);
    }
  }

  @Override
  protected ControlPlaneEmitter.StatusContext statusContext(long tps) {
    return baseStatusContext(tps, builder -> builder
        .traffic(Topology.EXCHANGE)
        .enabled(enabled)
        .data("intervalMs", config.getIntervalMs())
        .data("actionType", config.getActionType())
        .data("command", config.getCommand())
        .data("url", config.getUrl())
        .data("method", config.getMethod())
        .data("body", config.getBody())
        .data("headers", config.getHeaders())
        .data("lastRunTs", lastRun));
  }

  @Override
  protected String statusLogDetails(long tps) {
    return super.statusLogDetails(tps) + " enabled=" + enabled + " interval=" + config.getIntervalMs();
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

  private void emitConfigSuccess(ControlSignal cs) {
    String signal = requireText(cs.signal(), "signal");
    String correlationId = requireText(cs.correlationId(), "correlationId");
    String idempotencyKey = requireText(cs.idempotencyKey(), "idempotencyKey");
    CommandState state = currentState("completed");
    var context = ControlPlaneEmitter.ReadyContext.builder(signal, correlationId, idempotencyKey, state)
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
    var context = ControlPlaneEmitter.ErrorContext.builder(signal, correlationId, idempotencyKey, state, CONFIG_PHASE, code, message)
        .retryable(Boolean.FALSE)
        .result("error")
        .details(details)
        .build();
    String routingKey = ControlPlaneRouting.event("error", signal, confirmationScope());
    logControlSend(routingKey, "result=error code=" + code + " enabled=" + enabled);
    controlEmitter().emitError(context);
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
      HttpRequest.Builder builder = HttpRequest.newBuilder().uri(URI.create(config.getUrl()));
      String method = config.getMethod() == null ? "GET" : config.getMethod().toUpperCase();
      log.debug("[REST] REQ {} {} headers={} body={}", method, config.getUrl(), config.getHeaders(), snippet(config.getBody()));
      if (config.getBody() != null && !config.getBody().isEmpty()) {
        builder.method(method, HttpRequest.BodyPublishers.ofString(config.getBody()));
      } else {
        builder.method(method, HttpRequest.BodyPublishers.noBody());
      }
      for (Map.Entry<String, String> entry : config.getHeaders().entrySet()) {
        builder.header(entry.getKey(), entry.getValue());
      }
      HttpResponse<String> response = HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofString());
      log.debug("[REST] RESP {} {} status={} headers={} body={}",
          method,
          config.getUrl(),
          response.statusCode(),
          response.headers().map(),
          snippet(response.body()));
    } catch (Exception e) {
      log.warn("rest call", e);
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

  private CommandState currentState(String status) {
    return new CommandState(status, enabled, stateDetails());
  }

  private Map<String, Object> stateDetails() {
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("intervalMs", config.getIntervalMs());
    details.put("actionType", config.getActionType());
    details.put("command", config.getCommand());
    details.put("url", config.getUrl());
    details.put("method", config.getMethod());
    details.put("body", config.getBody());
    details.put("headers", config.getHeaders());
    details.put("lastRunTs", lastRun);
    return details;
  }
}
