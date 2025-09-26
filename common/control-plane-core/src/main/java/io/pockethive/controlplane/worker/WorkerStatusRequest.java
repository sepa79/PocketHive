package io.pockethive.controlplane.worker;

import io.pockethive.control.ControlSignal;
import io.pockethive.controlplane.consumer.ControlSignalEnvelope;

/**
 * Represents an incoming <code>status-request</code> control signal.
 */
public record WorkerStatusRequest(ControlSignalEnvelope envelope, String payload) {

    public ControlSignal signal() {
        return envelope.signal();
    }
}

