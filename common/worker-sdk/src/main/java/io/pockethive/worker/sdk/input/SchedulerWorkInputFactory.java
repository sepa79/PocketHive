package io.pockethive.worker.sdk.input;

import io.pockethive.work.api.ScheduledInvocationPolicy;
import io.pockethive.controlplane.ControlPlaneIdentity;
import io.pockethive.worker.sdk.config.SchedulerInputProperties;
import io.pockethive.work.config.binding.WorkInputConfig;
import io.pockethive.work.config.WorkerInputType;
import io.pockethive.worker.sdk.runtime.WorkerControlPlaneRuntime;
import io.pockethive.worker.sdk.runtime.WorkerDefinition;
import io.pockethive.worker.sdk.runtime.WorkerRuntime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Creates {@link SchedulerWorkInput} instances for workers that opt into scheduler-driven
 * invocation via {@link WorkerInputType#SCHEDULER}. The actual rate is owned by
 * {@link SchedulerInputProperties} (IO configuration), not the worker config.
 * <p>
 * Responsibility: compose scheduler intake with an explicitly supplied invocation policy.
 * Must not: select business policy from role names or compete with service factories.
 * Contract: RESP-WORK-SCHEDULE-INPUT — docs/architecture/runtime-responsibilities.md#resp-work-schedule-input.
 */
public final class SchedulerWorkInputFactory implements WorkInputFactory {

    private final ScheduledInvocationPolicy<?> policy;
    private final WorkerRuntime workerRuntime;
    private final WorkerControlPlaneRuntime controlPlaneRuntime;
    private final ControlPlaneIdentity identity;

    public SchedulerWorkInputFactory(
        WorkerRuntime workerRuntime,
        WorkerControlPlaneRuntime controlPlaneRuntime,
        ControlPlaneIdentity identity,
        ScheduledInvocationPolicy<?> policy
    ) {
        this.workerRuntime = workerRuntime;
        this.controlPlaneRuntime = controlPlaneRuntime;
        this.identity = identity;
        this.policy = java.util.Objects.requireNonNull(policy, "policy");
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
        ScheduledInvocationPolicy<Object> schedulerState = (ScheduledInvocationPolicy<Object>) policy;
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

}
