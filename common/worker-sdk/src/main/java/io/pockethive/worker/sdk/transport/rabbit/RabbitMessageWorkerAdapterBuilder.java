package io.pockethive.worker.sdk.transport.rabbit;

import io.pockethive.controlplane.ControlPlaneIdentity;
import io.pockethive.rabbit.api.RabbitListeners;
import io.pockethive.worker.sdk.runtime.WorkerControlPlaneRuntime;
import io.pockethive.worker.sdk.runtime.WorkerDefinition;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;
import org.slf4j.Logger;

/**
 * Responsibility: assemble the existing Work dispatch/state bridge with explicit collaborators.
 * Must not: configure broker settings or offer an alternate output publisher.
 * Contract: RESP-WORK-RABBIT-TRANSPORT — docs/architecture/runtime-responsibilities.md#resp-work-rabbit-transport.
 */
public final class RabbitMessageWorkerAdapterBuilder {
    Logger log;
    String listenerId;
    String displayName;
    WorkerDefinition workerDefinition;
    WorkerControlPlaneRuntime controlPlaneRuntime;
    RabbitListeners listenerRegistry;
    ControlPlaneIdentity identity;
    Function<WorkerControlPlaneRuntime.WorkerStateSnapshot, Boolean> desiredStateResolver;
    RabbitWorkDispatcher dispatcher;
    Consumer<Exception> dispatchErrorHandler;
    boolean emitWorkErrorAlerts = true;

    public RabbitMessageWorkerAdapterBuilder logger(Logger value) { log = value; return this; }
    public RabbitMessageWorkerAdapterBuilder listenerId(String value) { listenerId = value; return this; }
    public RabbitMessageWorkerAdapterBuilder displayName(String value) { displayName = value; return this; }
    public RabbitMessageWorkerAdapterBuilder workerDefinition(WorkerDefinition value) { workerDefinition = value; return this; }
    public RabbitMessageWorkerAdapterBuilder controlPlaneRuntime(WorkerControlPlaneRuntime value) { controlPlaneRuntime = value; return this; }
    public RabbitMessageWorkerAdapterBuilder listenerRegistry(RabbitListeners value) { listenerRegistry = value; return this; }
    public RabbitMessageWorkerAdapterBuilder identity(ControlPlaneIdentity value) { identity = value; return this; }
    public RabbitMessageWorkerAdapterBuilder desiredStateResolver(Function<WorkerControlPlaneRuntime.WorkerStateSnapshot, Boolean> value) { desiredStateResolver = value; return this; }
    public RabbitMessageWorkerAdapterBuilder dispatcher(RabbitWorkDispatcher value) { dispatcher = value; return this; }
    public RabbitMessageWorkerAdapterBuilder dispatchErrorHandler(Consumer<Exception> value) { dispatchErrorHandler = value; return this; }
    public RabbitMessageWorkerAdapterBuilder emitWorkErrorAlerts(boolean value) { emitWorkErrorAlerts = value; return this; }
    public RabbitMessageWorkerAdapter build() {
        Objects.requireNonNull(log, "log"); Objects.requireNonNull(listenerId, "listenerId");
        Objects.requireNonNull(displayName, "displayName"); Objects.requireNonNull(workerDefinition, "workerDefinition");
        Objects.requireNonNull(controlPlaneRuntime, "controlPlaneRuntime"); Objects.requireNonNull(listenerRegistry, "listenerRegistry");
        Objects.requireNonNull(identity, "identity"); Objects.requireNonNull(desiredStateResolver, "desiredStateResolver");
        Objects.requireNonNull(dispatcher, "dispatcher");
        if (dispatchErrorHandler == null) dispatchErrorHandler = ex -> log.warn("{} worker invocation failed", displayName, ex);
        return new RabbitMessageWorkerAdapter(this);
    }
}
