package io.pockethive.controlplane.consumer;

import io.pockethive.controlplane.ControlPlaneIdentity;

/**
 * Strategy to decide whether the current component should process a signal.
 */
@FunctionalInterface
public interface SelfFilter {

    SelfFilter NONE = (identity, envelope) -> true;

    boolean shouldProcess(ControlPlaneIdentity identity, ControlSignalEnvelope envelope);

    static SelfFilter skipSelfInstance() {
        return (identity, envelope) -> {
            if (identity == null || envelope == null) {
                return true;
            }
            return !envelope.targets(identity);
        };
    }
}
