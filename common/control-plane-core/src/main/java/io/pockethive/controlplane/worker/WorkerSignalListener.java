package io.pockethive.controlplane.worker;

import io.pockethive.controlplane.consumer.ControlSignalEnvelope;

/**
 * Callbacks invoked by {@link WorkerControlPlane} when worker-facing control
 * signals are received.
 */
public interface WorkerSignalListener {

    /**
     * Invoked when a <code>config-update</code> control signal is delivered to the worker.
     */
    default void onConfigUpdate(WorkerConfigCommand command) {
        // default no-op
    }

    /**
     * Invoked when a <code>status-request</code> control signal targets the worker.
     */
    default void onStatusRequest(WorkerStatusRequest request) {
        // default no-op
    }

    /**
     * Invoked for control signals that are not recognised by the worker helper.
     */
    default void onUnsupported(WorkerSignalContext context) {
        // default no-op
    }

    /**
     * Convenience context wrapper that exposes the raw signal envelope and payload.
     */
    record WorkerSignalContext(ControlSignalEnvelope envelope, String payload) { }
}

