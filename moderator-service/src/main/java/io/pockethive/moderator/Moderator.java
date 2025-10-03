package io.pockethive.moderator;

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
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
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

@Component
@EnableScheduling
public class Moderator extends AbstractWorkerRuntime {

  private static final Logger log = LoggerFactory.getLogger(Moderator.class);
  private static final String ROLE = "moderator";
  private static final long STATUS_INTERVAL_MS = 5000L;
  private static final String CONFIG_PHASE = "apply";

  private final RabbitTemplate rabbit;
  private final RabbitListenerEndpointRegistry registry;
  private final ListenerLifecycle workListenerLifecycle;
  private final AtomicLong counter = new AtomicLong();
  private volatile boolean enabled;
  private volatile long lastStatusTs = System.currentTimeMillis();

  public Moderator(RabbitTemplate rabbit,
                   RabbitListenerEndpointRegistry registry,
                   @Qualifier("moderatorControlPlaneEmitter") ControlPlaneEmitter controlEmitter,
                   @Qualifier("workerControlPlaneIdentity") ControlPlaneIdentity identity,
                   @Qualifier("workerControlPlaneTopologyDescriptor") ControlPlaneTopologyDescriptor topology,
                   WorkerControlPlane controlPlane) {
    super(log, controlEmitter, controlPlane, identity, topology);
    this.rabbit = Objects.requireNonNull(rabbit, "rabbit");
    this.registry = Objects.requireNonNull(registry, "registry");
    this.workListenerLifecycle = listenerLifecycle(
        () -> updateListenerState(true),
        () -> updateListenerState(false));
    try {
      sendStatusFull(0);
    } catch (Exception ignore) {
      // best-effort during startup
    }
  }

  @RabbitListener(id = "workListener", queues = "${pockethive.worker.queues.gen:${ph.genQueue:ph.default.gen}}")
  public void onGenerated(Message message,
                          @Header(value = "x-ph-service", required = false) String service,
                          @Header(value = ObservabilityContextUtil.HEADER, required = false) String trace) {
    Instant received = Instant.now();
    ObservabilityContext ctx = ObservabilityContextUtil.fromHeader(trace);
    if (ctx == null) {
      ctx = ObservabilityContextUtil.init(ROLE, identity().instanceId());
      ctx.getHops().clear();
    }
    ObservabilityContextUtil.populateMdc(ctx);
    try {
      if (enabled) {
        counter.incrementAndGet();
        Instant processed = Instant.now();
        ObservabilityContextUtil.appendHop(ctx, ROLE, identity().instanceId(), received, processed);
        Message out = MessageBuilder.fromMessage(message)
            .setHeader("x-ph-service", ROLE)
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
      applyEnabled(command.enabled());
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
        .workIn(Topology.GEN_QUEUE)
        .workRoutes(Topology.GEN_QUEUE)
        .workOut(Topology.MOD_QUEUE)
        .enabled(enabled));
  }

  @Override
  protected String statusLogDetails(long tps) {
    return super.statusLogDetails(tps) + " enabled=" + enabled;
  }

  private void applyEnabled(Boolean desired) {
    if (desired == null) {
      return;
    }
    boolean newEnabled = desired;
    boolean changed = newEnabled != enabled;
    enabled = newEnabled;
    if (changed) {
      workListenerLifecycle.apply(enabled);
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

  private CommandState currentState(String status) {
    return new CommandState(status, enabled, stateDetails());
  }

  private Map<String, Object> stateDetails() {
    return Map.of();
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
