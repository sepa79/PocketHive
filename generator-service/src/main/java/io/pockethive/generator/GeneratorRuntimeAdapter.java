package io.pockethive.generator;

import io.pockethive.Topology;
import io.pockethive.controlplane.ControlPlaneIdentity;
import io.pockethive.worker.sdk.api.WorkMessage;
import io.pockethive.worker.sdk.api.WorkResult;
import io.pockethive.worker.sdk.config.WorkerType;
import io.pockethive.worker.sdk.runtime.WorkerControlPlaneRuntime;
import io.pockethive.worker.sdk.runtime.WorkerDefinition;
import io.pockethive.worker.sdk.runtime.WorkerRegistry;
import io.pockethive.worker.sdk.runtime.WorkerRuntime;
import io.pockethive.worker.sdk.transport.rabbit.RabbitWorkMessageConverter;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
class GeneratorRuntimeAdapter {

  private static final Logger log = LoggerFactory.getLogger(GeneratorRuntimeAdapter.class);

  private final WorkerRuntime workerRuntime;
  private final WorkerRegistry workerRegistry;
  private final WorkerControlPlaneRuntime controlPlaneRuntime;
  private final RabbitTemplate rabbitTemplate;
  private final ControlPlaneIdentity identity;
  private final GeneratorDefaults defaults;
  private final RabbitWorkMessageConverter messageConverter = new RabbitWorkMessageConverter();
  private final Map<String, GeneratorState> states = new ConcurrentHashMap<>();
  private final List<WorkerDefinition> generatorWorkers;

  GeneratorRuntimeAdapter(WorkerRuntime workerRuntime,
                          WorkerRegistry workerRegistry,
                          WorkerControlPlaneRuntime controlPlaneRuntime,
                          RabbitTemplate rabbitTemplate,
                          ControlPlaneIdentity identity,
                          GeneratorDefaults defaults) {
    this.workerRuntime = Objects.requireNonNull(workerRuntime, "workerRuntime");
    this.workerRegistry = Objects.requireNonNull(workerRegistry, "workerRegistry");
    this.controlPlaneRuntime = Objects.requireNonNull(controlPlaneRuntime, "controlPlaneRuntime");
    this.rabbitTemplate = Objects.requireNonNull(rabbitTemplate, "rabbitTemplate");
    this.identity = Objects.requireNonNull(identity, "identity");
    this.defaults = Objects.requireNonNull(defaults, "defaults");
    this.generatorWorkers = workerRegistry.all().stream()
        .filter(definition -> definition.workerType() == WorkerType.GENERATOR)
        .toList();
    initialiseStateListeners();
  }

  @PostConstruct
  void emitInitialStatus() {
    controlPlaneRuntime.emitStatusSnapshot();
  }

  @Scheduled(fixedRate = 1000)
  public void tick() {
    for (WorkerDefinition definition : generatorWorkers) {
      GeneratorState state = states.get(definition.beanName());
      if (state == null) {
        continue;
      }
      if (!state.isEnabled()) {
        continue;
      }
      int quota = state.nextQuota();
      for (int i = 0; i < quota; i++) {
        invokeWorker(definition);
      }
      if (state.consumeSingleRequest()) {
        invokeWorker(definition);
      }
    }
  }

  @Scheduled(fixedRate = 5000)
  public void emitStatusDelta() {
    controlPlaneRuntime.emitStatusDelta();
  }

  private void initialiseStateListeners() {
    for (WorkerDefinition definition : generatorWorkers) {
      GeneratorWorkerConfig initialConfig = defaults.asConfig();
      controlPlaneRuntime.registerDefaultConfig(definition.beanName(), initialConfig);
      GeneratorState state = new GeneratorState(initialConfig);
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
      publishResult(definition, result);
    } catch (Exception ex) {
      log.warn("Generator worker {} invocation failed", definition.beanName(), ex);
    }
  }

  private void publishResult(WorkerDefinition definition, WorkResult result) {
    if (!(result instanceof WorkResult.Message messageResult)) {
      return;
    }
    Message message = messageConverter.toMessage(messageResult.value());
    String routingKey = resolveOutbound(definition);
    rabbitTemplate.send(Topology.EXCHANGE, routingKey, message);
  }

  private String resolveOutbound(WorkerDefinition definition) {
    String out = definition.resolvedOutQueue();
    if (out == null || out.isBlank()) {
      throw new IllegalStateException("Generator worker " + definition.beanName() + " has no outbound queue configured");
    }
    return out;
  }

  private static final class GeneratorState {

    private volatile GeneratorWorkerConfig config;
    private volatile boolean enabled;
    private final AtomicBoolean singleRequestPending = new AtomicBoolean(false);
    private double carryOver;

    GeneratorState(GeneratorWorkerConfig initial) {
      this.config = initial;
      this.enabled = initial.enabled();
      this.carryOver = 0.0;
    }

    synchronized void update(WorkerControlPlaneRuntime.WorkerStateSnapshot snapshot, GeneratorDefaults defaults) {
      GeneratorWorkerConfig incoming = snapshot.config(GeneratorWorkerConfig.class)
          .orElseGet(defaults::asConfig);
      GeneratorWorkerConfig previous = this.config;
      boolean resolvedEnabled = snapshot.enabled().orElseGet(() -> previous == null
          ? incoming.enabled()
          : previous.enabled());
      GeneratorWorkerConfig updated = new GeneratorWorkerConfig(
          resolvedEnabled,
          incoming.ratePerSec(),
          incoming.singleRequest(),
          incoming.path(),
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
      }
      if (!resolvedEnabled) {
        carryOver = 0.0;
      }
    }

    synchronized int nextQuota() {
      double rate = Math.max(0.0, config.ratePerSec());
      double planned = rate + carryOver;
      int whole = (int) Math.floor(planned);
      carryOver = planned - whole;
      return whole;
    }

    boolean consumeSingleRequest() {
      return singleRequestPending.getAndSet(false);
    }

    boolean isEnabled() {
      return enabled;
    }
  }
}
