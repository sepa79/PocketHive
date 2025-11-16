package io.pockethive.worker.sdk.input;

import io.pockethive.worker.sdk.runtime.WorkerControlPlaneRuntime;

/**
 * Baseline contract implemented by components that feed {@code WorkItem} instances into the worker
 * runtime. Inputs encapsulate transport or scheduling concerns (RabbitMQ listeners, cron schedulers,
 * webhooks, etc.) while delegating business processing to the unified worker interface.
 *
 * <p>Implementations are expected to be lightweight and idempotent; {@link #start()} may be invoked
 * multiple times during application bootstrap, and {@link #stop()} is called during shutdown or when
 * the desired state disables the worker. State updates arrive via {@link #update(WorkerControlPlaneRuntime.WorkerStateSnapshot)}
 * whenever the control plane publishes a new snapshot.</p>
 */
public interface WorkInput extends AutoCloseable {

    /**
     * Starts the input. Implementations should initialise listeners or scheduling loops.
     */
    default void start() throws Exception {
        // no-op
    }

    /**
     * Stops the input, releasing any resources acquired during {@link #start()}.
     */
    default void stop() throws Exception {
        // no-op
    }

    /**
     * Receives the latest control-plane snapshot describing desired worker state.
     *
     * @param snapshot control-plane view of the worker, as published by {@link WorkerControlPlaneRuntime}
     */
    default void update(WorkerControlPlaneRuntime.WorkerStateSnapshot snapshot) {
        // no-op
    }

    @Override
    default void close() throws Exception {
        stop();
    }
}
