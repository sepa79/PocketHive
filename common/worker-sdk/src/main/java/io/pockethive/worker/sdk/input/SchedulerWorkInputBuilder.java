package io.pockethive.worker.sdk.input;

import io.pockethive.work.api.ScheduledInvocationPolicy;
import io.pockethive.controlplane.ControlPlaneIdentity;
import io.pockethive.work.api.WorkItem;
import io.pockethive.worker.sdk.config.SchedulerInputProperties;
import io.pockethive.worker.sdk.runtime.WorkerControlPlaneRuntime;
import io.pockethive.worker.sdk.runtime.WorkerDefinition;
import io.pockethive.worker.sdk.runtime.WorkerRuntime;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import org.slf4j.Logger;

/**
 * Responsibility: construct one explicitly configured scheduler input.
 * Must not: select a business policy, repair settings or own worker state.
 * Contract: RESP-WORK-SCHEDULE-INPUT — docs/architecture/runtime-responsibilities.md#resp-work-schedule-input.
 */
public final class SchedulerWorkInputBuilder<C> {

    WorkerDefinition workerDefinition;
    WorkerControlPlaneRuntime controlPlaneRuntime;
    WorkerRuntime workerRuntime;
    ControlPlaneIdentity identity;
    ScheduledInvocationPolicy<C> schedulerState;
    BiFunction<WorkerDefinition, ControlPlaneIdentity, WorkItem> seedFactory = SchedulerWorkInput::defaultSeed;
    BiConsumer<WorkItem, WorkerDefinition> resultHandler = SchedulerWorkInput::ignoreResult;
    Consumer<Exception> dispatchErrorHandler = ex -> SchedulerWorkInput.defaultLog.warn("Scheduler worker invocation failed", ex);
    Logger log = SchedulerWorkInput.defaultLog;
    SchedulerInputProperties scheduling;

    SchedulerWorkInputBuilder() {
    }

    public SchedulerWorkInputBuilder<C> workerDefinition(WorkerDefinition workerDefinition) {
        this.workerDefinition = Objects.requireNonNull(workerDefinition, "workerDefinition");
        return this;
    }

    public SchedulerWorkInputBuilder<C> controlPlaneRuntime(WorkerControlPlaneRuntime controlPlaneRuntime) {
        this.controlPlaneRuntime = Objects.requireNonNull(controlPlaneRuntime, "controlPlaneRuntime");
        return this;
    }

    public SchedulerWorkInputBuilder<C> workerRuntime(WorkerRuntime workerRuntime) {
        this.workerRuntime = Objects.requireNonNull(workerRuntime, "workerRuntime");
        return this;
    }

    public SchedulerWorkInputBuilder<C> identity(ControlPlaneIdentity identity) {
        this.identity = Objects.requireNonNull(identity, "identity");
        return this;
    }

    public SchedulerWorkInputBuilder<C> schedulerState(ScheduledInvocationPolicy<C> schedulerState) {
        this.schedulerState = Objects.requireNonNull(schedulerState, "schedulerState");
        return this;
    }

    public SchedulerWorkInputBuilder<C> seedFactory(
        BiFunction<WorkerDefinition, ControlPlaneIdentity, WorkItem> seedFactory
    ) {
        this.seedFactory = Objects.requireNonNull(seedFactory, "seedFactory");
        return this;
    }

    public SchedulerWorkInputBuilder<C> resultHandler(
        BiConsumer<WorkItem, WorkerDefinition> resultHandler
    ) {
        this.resultHandler = Objects.requireNonNull(resultHandler, "resultHandler");
        return this;
    }

    public SchedulerWorkInputBuilder<C> scheduling(SchedulerInputProperties properties) {
        this.scheduling = Objects.requireNonNull(properties, "properties");
        return this;
    }

    public SchedulerWorkInputBuilder<C> dispatchErrorHandler(Consumer<Exception> dispatchErrorHandler) {
        this.dispatchErrorHandler = Objects.requireNonNull(dispatchErrorHandler, "dispatchErrorHandler");
        return this;
    }

    public SchedulerWorkInputBuilder<C> logger(Logger log) {
        this.log = Objects.requireNonNull(log, "log");
        return this;
    }

    public SchedulerWorkInput<C> build() {
        Objects.requireNonNull(workerDefinition, "workerDefinition");
        Objects.requireNonNull(controlPlaneRuntime, "controlPlaneRuntime");
        Objects.requireNonNull(workerRuntime, "workerRuntime");
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(schedulerState, "schedulerState");
        Objects.requireNonNull(seedFactory, "seedFactory");
        Objects.requireNonNull(resultHandler, "resultHandler");
        Objects.requireNonNull(dispatchErrorHandler, "dispatchErrorHandler");
        Objects.requireNonNull(log, "log");
        Objects.requireNonNull(scheduling, "scheduling");
        scheduling.validateConfigured("inputs.scheduler");
        return new SchedulerWorkInput<>(this);
    }
}
