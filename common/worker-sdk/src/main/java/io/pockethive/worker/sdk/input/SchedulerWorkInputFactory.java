package io.pockethive.worker.sdk.input;

import io.pockethive.controlplane.ControlPlaneIdentity;
import io.pockethive.worker.sdk.config.SchedulerInputProperties;
import io.pockethive.worker.sdk.config.WorkInputConfig;
import io.pockethive.worker.sdk.config.WorkerInputType;
import io.pockethive.worker.sdk.runtime.WorkerControlPlaneRuntime;
import io.pockethive.worker.sdk.runtime.WorkerDefinition;
import io.pockethive.worker.sdk.runtime.WorkerRuntime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;

/**
 * Creates {@link SchedulerWorkInput} instances for workers that opt into scheduler-driven
 * invocation via {@link WorkerInputType#SCHEDULER}. The actual rate is owned by
 * {@link SchedulerInputProperties} (IO configuration), not the worker config.
 */
public final class SchedulerWorkInputFactory implements WorkInputFactory, Ordered {

    private final WorkerRuntime workerRuntime;
    private final WorkerControlPlaneRuntime controlPlaneRuntime;
    private final ControlPlaneIdentity identity;

    public SchedulerWorkInputFactory(
        WorkerRuntime workerRuntime,
        WorkerControlPlaneRuntime controlPlaneRuntime,
        ControlPlaneIdentity identity
    ) {
        this.workerRuntime = workerRuntime;
        this.controlPlaneRuntime = controlPlaneRuntime;
        this.identity = identity;
    }

    @Override
    public boolean supports(WorkerDefinition definition) {
        return definition.input() == WorkerInputType.SCHEDULER;
    }

    @SuppressWarnings("unchecked")
    @Override
    public WorkInput create(WorkerDefinition definition, WorkInputConfig config) {
        Logger logger = LoggerFactory.getLogger(definition.beanType());
        Class<?> configType = definition.configType();
        Class<Object> typedConfigType = (Class<Object>) configType;
        if (!(config instanceof SchedulerInputProperties scheduling)) {
            throw new IllegalStateException("Scheduler inputs require SchedulerInputProperties configuration");
        }
        SchedulerState<Object> schedulerState = SchedulerStates.ratePerSecond(
            typedConfigType,
            logger,
            scheduling::isEnabled,
            scheduling::getRatePerSec
        );
        return SchedulerWorkInput.<Object>builder()
            .workerDefinition(definition)
            .controlPlaneRuntime(controlPlaneRuntime)
            .workerRuntime(workerRuntime)
            .identity(identity)
            .schedulerState(schedulerState)
            .scheduling(scheduling)
            .logger(logger)
            .build();
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
