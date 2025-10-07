package io.pockethive.trigger;

import io.pockethive.controlplane.ControlPlaneIdentity;
import io.pockethive.observability.ObservabilityContext;
import io.pockethive.observability.ObservabilityContextUtil;
import io.pockethive.worker.sdk.api.WorkMessage;
import io.pockethive.worker.sdk.api.WorkResult;
import io.pockethive.worker.sdk.config.WorkerType;
import io.pockethive.worker.sdk.runtime.WorkerControlPlaneRuntime;
import io.pockethive.worker.sdk.runtime.WorkerDefinition;
import io.pockethive.worker.sdk.runtime.WorkerRegistry;
import io.pockethive.worker.sdk.runtime.WorkerRuntime;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
class TriggerRuntimeAdapter {

  private static final Logger log = LoggerFactory.getLogger(TriggerRuntimeAdapter.class);

  private final WorkerRuntime workerRuntime;
  private final WorkerRegistry workerRegistry;
  private final WorkerControlPlaneRuntime controlPlaneRuntime;
  private final ControlPlaneIdentity identity;
  private final TriggerDefaults defaults;
  private final Clock clock;
  private final Map<String, TriggerState> states = new ConcurrentHashMap<>();
  private final List<WorkerDefinition> triggerWorkers;

  TriggerRuntimeAdapter(WorkerRuntime workerRuntime,
                        WorkerRegistry workerRegistry,
                        WorkerControlPlaneRuntime controlPlaneRuntime,
                        ControlPlaneIdentity identity,
                        TriggerDefaults defaults) {
    this(workerRuntime, workerRegistry, controlPlaneRuntime, identity, defaults, Clock.systemUTC());
  }

  TriggerRuntimeAdapter(WorkerRuntime workerRuntime,
                        WorkerRegistry workerRegistry,
                        WorkerControlPlaneRuntime controlPlaneRuntime,
                        ControlPlaneIdentity identity,
                        TriggerDefaults defaults,
                        Clock clock) {
    this.workerRuntime = Objects.requireNonNull(workerRuntime, "workerRuntime");
    this.workerRegistry = Objects.requireNonNull(workerRegistry, "workerRegistry");
    this.controlPlaneRuntime = Objects.requireNonNull(controlPlaneRuntime, "controlPlaneRuntime");
    this.identity = Objects.requireNonNull(identity, "identity");
    this.defaults = Objects.requireNonNull(defaults, "defaults");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.triggerWorkers = workerRegistry.all().stream()
        .filter(definition -> definition.workerType() == WorkerType.GENERATOR)
        .filter(definition -> "trigger".equals(definition.role()))
        .toList();
    initialiseStateListeners();
  }

  @PostConstruct
  void emitInitialStatus() {
    controlPlaneRuntime.emitStatusSnapshot();
  }

  @Scheduled(fixedRate = 1000)
  public void tick() {
    long now = clock.millis();
    for (WorkerDefinition definition : triggerWorkers) {
      TriggerState state = states.get(definition.beanName());
      if (state == null) {
        continue;
      }
      if (state.consumeSingleRequest()) {
        invokeWorker(definition);
        continue;
      }
      if (!state.isEnabled()) {
        continue;
      }
      if (state.shouldInvoke(now)) {
        invokeWorker(definition);
      }
    }
  }

  @Scheduled(fixedRate = 5000)
  public void emitStatusDelta() {
    controlPlaneRuntime.emitStatusDelta();
  }

  @RabbitListener(queues = "#{@triggerControlQueueName}")
  public void onControl(String payload,
                        @Header(AmqpHeaders.RECEIVED_ROUTING_KEY) String routingKey,
                        @Header(value = ObservabilityContextUtil.HEADER, required = false) String traceHeader) {
    ObservabilityContext context = ObservabilityContextUtil.fromHeader(traceHeader);
    ObservabilityContextUtil.populateMdc(context);
    try {
      if (routingKey == null || routingKey.isBlank()) {
        throw new IllegalArgumentException("Control routing key must not be null or blank");
      }
      if (payload == null || payload.isBlank()) {
        throw new IllegalArgumentException("Control payload must not be null or blank");
      }
      boolean handled = controlPlaneRuntime.handle(payload, routingKey);
      if (!handled) {
        log.debug("Ignoring control-plane payload on routing key {}", routingKey);
      }
    } finally {
      MDC.clear();
    }
  }

  private void initialiseStateListeners() {
    for (WorkerDefinition definition : triggerWorkers) {
      TriggerState state = new TriggerState(defaults.asConfig());
      states.put(definition.beanName(), state);
      controlPlaneRuntime.registerStateListener(definition.beanName(), snapshot -> state.update(snapshot, defaults));
    }
  }

  private void invokeWorker(WorkerDefinition definition) {
    WorkMessage seed = WorkMessage.builder()
        .header("swarmId", identity.swarmId())
        .header("instanceId", identity.instanceId())
        .build();
    try {
      WorkResult result = workerRuntime.dispatch(definition.beanName(), seed);
      if (!(result instanceof WorkResult.None)) {
        log.debug("Trigger worker {} returned result {}", definition.beanName(), result.getClass().getSimpleName());
      }
    } catch (Exception ex) {
      log.warn("Trigger worker {} invocation failed", definition.beanName(), ex);
    }
  }

  private static final class TriggerState {

    private volatile TriggerWorkerConfig config;
    private volatile boolean enabled;
    private volatile long lastInvocation;
    private final AtomicBoolean singleRequestPending = new AtomicBoolean(false);

    private TriggerState(TriggerWorkerConfig initial) {
      this.config = Objects.requireNonNull(initial, "initial");
      this.enabled = initial.enabled();
      this.lastInvocation = 0L;
    }

    synchronized void update(WorkerControlPlaneRuntime.WorkerStateSnapshot snapshot, TriggerDefaults defaults) {
      Objects.requireNonNull(snapshot, "snapshot");
      TriggerWorkerConfig incoming = snapshot.config(TriggerWorkerConfig.class)
          .orElseGet(defaults::asConfig);
      TriggerWorkerConfig previous = this.config;
      boolean resolvedEnabled = snapshot.enabled().orElseGet(() -> previous == null
          ? incoming.enabled()
          : previous.enabled());
      TriggerWorkerConfig updated = new TriggerWorkerConfig(
          resolvedEnabled,
          incoming.intervalMs(),
          incoming.singleRequest(),
          incoming.actionType(),
          incoming.command(),
          incoming.url(),
          incoming.method(),
          incoming.body(),
          incoming.headers()
      );
      this.config = updated;
      this.enabled = resolvedEnabled;
      if (previous == null || !previous.equals(updated)) {
        if (updated.singleRequest()) {
          singleRequestPending.set(true);
        }
        if (!resolvedEnabled) {
          lastInvocation = 0L;
        }
      }
    }

    synchronized boolean shouldInvoke(long now) {
      long interval = Math.max(0L, config.intervalMs());
      if (now - lastInvocation >= interval) {
        lastInvocation = now;
        return true;
      }
      return false;
    }

    boolean consumeSingleRequest() {
      return singleRequestPending.getAndSet(false);
    }

    boolean isEnabled() {
      return enabled;
    }
  }
}
