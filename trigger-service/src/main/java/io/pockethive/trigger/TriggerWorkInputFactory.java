package io.pockethive.trigger;

import io.pockethive.controlplane.ControlPlaneIdentity;
import io.pockethive.worker.sdk.config.SchedulerInputProperties;
import io.pockethive.worker.sdk.config.WorkInputConfig;
import io.pockethive.worker.sdk.config.WorkerInputType;
import io.pockethive.worker.sdk.input.SchedulerWorkInput;
import io.pockethive.worker.sdk.input.WorkInput;
import io.pockethive.worker.sdk.input.WorkInputFactory;
import io.pockethive.worker.sdk.runtime.WorkerControlPlaneRuntime;
import io.pockethive.worker.sdk.runtime.WorkerDefinition;
import io.pockethive.worker.sdk.runtime.WorkerRuntime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
class TriggerWorkInputFactory implements WorkInputFactory {

  private final WorkerRuntime workerRuntime;
  private final WorkerControlPlaneRuntime controlPlaneRuntime;
  private final ControlPlaneIdentity identity;
  private final TriggerWorkerProperties properties;

  TriggerWorkInputFactory(
      WorkerRuntime workerRuntime,
      WorkerControlPlaneRuntime controlPlaneRuntime,
      ControlPlaneIdentity identity,
      TriggerWorkerProperties properties
  ) {
    this.workerRuntime = workerRuntime;
    this.controlPlaneRuntime = controlPlaneRuntime;
    this.identity = identity;
    this.properties = properties;
  }

  @Override
  public boolean supports(WorkerDefinition definition) {
    return definition.input() == WorkerInputType.SCHEDULER
        && "trigger".equalsIgnoreCase(definition.role());
  }

  @Override
  public WorkInput create(WorkerDefinition definition, WorkInputConfig config) {
    Logger logger = LoggerFactory.getLogger(definition.beanType());
    SchedulerInputProperties scheduling = config instanceof SchedulerInputProperties props
        ? props
        : new SchedulerInputProperties();
    TriggerSchedulerState schedulerState = new TriggerSchedulerState(properties, scheduling.isEnabled());
    return SchedulerWorkInput.<TriggerWorkerConfig>builder()
        .workerDefinition(definition)
        .controlPlaneRuntime(controlPlaneRuntime)
        .workerRuntime(workerRuntime)
        .identity(identity)
        .schedulerState(schedulerState)
        .scheduling(scheduling)
        .logger(logger)
        .build();
  }
}
