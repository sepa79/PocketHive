package io.pockethive.moderator;

import io.pockethive.Topology;
import io.pockethive.control.CommandState;
import io.pockethive.control.ControlSignal;
import io.pockethive.control.ConfirmationScope;
import io.pockethive.observability.ObservabilityContext;
import io.pockethive.observability.ObservabilityContextUtil;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

@Component
@EnableScheduling
public class Moderator {

  private static final Logger log = LoggerFactory.getLogger(Moderator.class);
  private static final String ROLE = "moderator";
  private static final long STATUS_INTERVAL_MS = 5000L;
  private static final String CONFIG_PHASE = "apply";

  private final RabbitTemplate rabbit;
  private final RabbitListenerEndpointRegistry registry;
  private final ControlPlaneEmitter controlEmitter;
  private final WorkerControlPlane controlPlane;
  private final WorkerSignalListener controlListener;
  private final ControlPlaneIdentity identity;
  private final ConfirmationScope confirmationScope;
  private final String swarmId;
  private final String instanceId;
  private final String controlQueueName;
  private final String[] controlRoutes;
  private final AtomicLong counter = new AtomicLong();
  private volatile boolean enabled = false;
  private volatile long lastStatusTs = System.currentTimeMillis();

  public Moderator(RabbitTemplate rabbit,
                   RabbitListenerEndpointRegistry registry,
                   @Qualifier("moderatorControlPlaneEmitter") ControlPlaneEmitter controlEmitter,
                   @Qualifier("workerControlPlaneIdentity") ControlPlaneIdentity identity,
                   @Qualifier("workerControlPlaneTopologyDescriptor") ControlPlaneTopologyDescriptor topology,
                   WorkerControlPlane controlPlane) {
    this.rabbit = Objects.requireNonNull(rabbit, "rabbit");
    this.registry = Objects.requireNonNull(registry, "registry");
    this.controlEmitter = Objects.requireNonNull(controlEmitter, "controlEmitter");
    this.controlPlane = Objects.requireNonNull(controlPlane, "controlPlane");
    this.identity = Objects.requireNonNull(identity, "identity");
    this.swarmId = identity.swarmId();
    this.instanceId = identity.instanceId();
    this.confirmationScope = new ConfirmationScope(swarmId, identity.role(), instanceId);
    ControlPlaneTopologyDescriptor descriptor = Objects.requireNonNull(topology, "topology");
    this.controlQueueName = descriptor.controlQueue(instanceId)
        .map(ControlQueueDescriptor::name)
        .orElseThrow(() -> new IllegalStateException("Moderator control queue descriptor is missing"));
    this.controlRoutes = resolveRoutes(descriptor, identity);
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
    try {
      sendStatusFull(0);
    } catch (Exception ignore) {
      // best-effort during startup
    }
  }

  @RabbitListener(id = "workListener", queues = "${ph.genQueue:ph.default.gen}")
  public void onGenerated(Message message,
                          @Header(value = "x-ph-service", required = false) String service,
                          @Header(value = ObservabilityContextUtil.HEADER, required = false) String trace) {
    Instant received = Instant.now();
    ObservabilityContext ctx = ObservabilityContextUtil.fromHeader(trace);
    if (ctx == null) {
      ctx = ObservabilityContextUtil.init("moderator", instanceId);
      ctx.getHops().clear();
    }
    ObservabilityContextUtil.populateMdc(ctx);
    try {
      if (enabled) {
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

  @RabbitListener(queues = "#{@moderatorControlQueueName}")
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
      applyEnabled(command.enabled());
      emitConfigSuccess(cs);
    } catch (Exception e) {
      log.warn("config update", e);
      emitConfigError(cs, e);
    }
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
    MessageListenerContainer container = registry.getListenerContainer("workListener");
    if (container != null) {
      if (desired) {
        container.start();
      } else {
        container.stop();
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
    logControlSend(routingKey, "tps=" + tps + " enabled=" + enabled);
    controlEmitter.emitStatusDelta(statusContext(tps));
  }

  private void sendStatusFull(long tps) {
    String routingKey = ControlPlaneRouting.event("status-full", confirmationScope);
    logControlSend(routingKey, "tps=" + tps + " enabled=" + enabled);
    controlEmitter.emitStatusSnapshot(statusContext(tps));
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

  private ControlPlaneEmitter.StatusContext statusContext(long tps) {
    return ControlPlaneEmitter.StatusContext.of(builder -> builder
        .traffic(Topology.EXCHANGE)
        .workIn(Topology.GEN_QUEUE)
        .workRoutes(Topology.GEN_QUEUE)
        .workOut(Topology.MOD_QUEUE)
        .controlIn(controlQueueName)
        .controlRoutes(controlRoutes)
        .enabled(enabled)
        .tps(tps));
  }

  private CommandState currentState(String status) {
    return new CommandState(status, enabled, stateDetails());
  }

  private Map<String, Object> stateDetails() {
    return Map.of();
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
}
