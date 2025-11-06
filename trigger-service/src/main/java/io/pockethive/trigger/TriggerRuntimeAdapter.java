package io.pockethive.trigger;

import io.pockethive.controlplane.ControlPlaneIdentity;
import io.pockethive.worker.sdk.config.WorkerInputType;
import io.pockethive.worker.sdk.input.WorkInputRegistry;
import io.pockethive.worker.sdk.input.SchedulerWorkInput;
import io.pockethive.worker.sdk.runtime.WorkerControlPlaneRuntime;
import io.pockethive.worker.sdk.runtime.WorkerDefinition;
import io.pockethive.worker.sdk.runtime.WorkerRegistry;
import io.pockethive.worker.sdk.runtime.WorkerRuntime;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

@Component
class TriggerRuntimeAdapter {

  private static final Logger log = LoggerFactory.getLogger(TriggerRuntimeAdapter.class);

  private final Clock clock;
  private final List<SchedulerWorkInput<TriggerWorkerConfig>> workInputs = new ArrayList<>();

  @Autowired
  TriggerRuntimeAdapter(WorkerRuntime workerRuntime,
                        WorkerRegistry workerRegistry,
                        WorkerControlPlaneRuntime controlPlaneRuntime,
                        ControlPlaneIdentity identity,
                        TriggerDefaults defaults,
                        WorkInputRegistry inputRegistry) {
    this(workerRuntime, workerRegistry, controlPlaneRuntime, identity, defaults, Clock.systemUTC(), inputRegistry);
  }

  TriggerRuntimeAdapter(WorkerRuntime workerRuntime,
                        WorkerRegistry workerRegistry,
                        WorkerControlPlaneRuntime controlPlaneRuntime,
                        ControlPlaneIdentity identity,
                        TriggerDefaults defaults,
                        Clock clock,
                        WorkInputRegistry inputRegistry) {
    Objects.requireNonNull(workerRuntime, "workerRuntime");
    WorkerRegistry registry = Objects.requireNonNull(workerRegistry, "workerRegistry");
    WorkerControlPlaneRuntime controlRuntime = Objects.requireNonNull(controlPlaneRuntime, "controlPlaneRuntime");
    ControlPlaneIdentity controlIdentity = Objects.requireNonNull(identity, "identity");
    TriggerDefaults triggerDefaults = Objects.requireNonNull(defaults, "defaults");
    this.clock = Objects.requireNonNull(clock, "clock");
    Objects.requireNonNull(inputRegistry, "inputRegistry");
    registry.streamByRoleAndInput("trigger", WorkerInputType.SCHEDULER)
        .forEach(definition -> {
          SchedulerWorkInput<TriggerWorkerConfig> input = SchedulerWorkInput.<TriggerWorkerConfig>builder()
              .workerDefinition(definition)
              .controlPlaneRuntime(controlRuntime)
              .workerRuntime(workerRuntime)
              .identity(controlIdentity)
              .schedulerState(new TriggerSchedulerState(triggerDefaults))
              .dispatchErrorHandler(ex -> log.warn("Trigger worker {} invocation failed", definition.beanName(), ex))
              .logger(log)
              .build();
          workInputs.add(input);
          inputRegistry.register(definition, input);
        });
  }

  @Scheduled(fixedRate = 1000)
  public void tick() {
    long now = clock.millis();
    workInputs.forEach(input -> input.tick(now));
  }

  @PostConstruct
  void onStart() {
    start();
  }

  void start() {
    workInputs.forEach(SchedulerWorkInput::start);
  }

  @PreDestroy
  void onStop() {
    stop();
  }

  void stop() {
    workInputs.forEach(SchedulerWorkInput::stop);
  }
}
