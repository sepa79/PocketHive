package io.pockethive.orchestrator.app;

/**
 * Swarm runtime status as reported by swarm-controller in status-full/status-delta context.
 * <p>
 * This is intentionally separate from the orchestrator lifecycle state machine.
 */
public enum SwarmRuntimeStatus {
    READY,
    STARTING,
    RUNNING,
    STOPPING,
    STOPPED,
    FAILED
}

