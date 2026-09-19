package io.pockethive.worker.sdk.input.message;

import io.pockethive.controlplane.ControlPlaneIdentity;
import io.pockethive.work.api.transport.WorkInputChannel;
import io.pockethive.worker.sdk.input.WorkMessageDispatcher;
import io.pockethive.worker.sdk.runtime.WorkerControlPlaneRuntime;
import io.pockethive.worker.sdk.runtime.WorkerDefinition;
import java.util.Objects;
import java.util.function.Consumer;
import org.slf4j.Logger;

/**
 * Responsibility: assemble the existing Work dispatch/state bridge with explicit collaborators.
 * Must not: configure broker settings or offer an alternate output publisher.
 * Contract: RESP-WORK-TRANSPORT — docs/architecture/runtime-responsibilities.md#resp-work-transport.
 */
public final class MessageWorkInputBuilder {
    Logger log;
    String displayName;
    WorkerDefinition workerDefinition;
    WorkerControlPlaneRuntime controlPlaneRuntime;
    WorkInputChannel channel;
    ControlPlaneIdentity identity;
    WorkMessageDispatcher dispatcher;
    Consumer<Exception> dispatchErrorHandler;
    boolean emitWorkErrorAlerts = true;

    public MessageWorkInputBuilder logger(Logger value) { log = value; return this; }
    public MessageWorkInputBuilder displayName(String value) { displayName = value; return this; }
    public MessageWorkInputBuilder workerDefinition(WorkerDefinition value) { workerDefinition = value; return this; }
    public MessageWorkInputBuilder controlPlaneRuntime(WorkerControlPlaneRuntime value) { controlPlaneRuntime = value; return this; }
    public MessageWorkInputBuilder channel(WorkInputChannel value) { channel = value; return this; }
    public MessageWorkInputBuilder identity(ControlPlaneIdentity value) { identity = value; return this; }
    public MessageWorkInputBuilder dispatcher(WorkMessageDispatcher value) { dispatcher = value; return this; }
    public MessageWorkInputBuilder dispatchErrorHandler(Consumer<Exception> value) { dispatchErrorHandler = value; return this; }
    public MessageWorkInputBuilder emitWorkErrorAlerts(boolean value) { emitWorkErrorAlerts = value; return this; }
    public MessageWorkInput build() {
        Objects.requireNonNull(log, "log");
        Objects.requireNonNull(displayName, "displayName"); Objects.requireNonNull(workerDefinition, "workerDefinition");
        Objects.requireNonNull(controlPlaneRuntime, "controlPlaneRuntime"); Objects.requireNonNull(channel, "channel");
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(dispatcher, "dispatcher");
        if (dispatchErrorHandler == null) dispatchErrorHandler = ex -> log.warn("{} worker invocation failed", displayName, ex);
        return new MessageWorkInput(this);
    }
}
