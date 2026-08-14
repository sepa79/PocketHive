package io.pockethive.swarmcontroller;

/**
 * Complete capability set exposed by the Spring Swarm Controller runtime.
 *
 * <p>Every capability is mandatory. Implementations cannot inherit no-op, success, {@code null},
 * or empty-value behavior for core state and configuration.
 */
public interface SwarmLifecycle
    extends SwarmLifecycleCore, SwarmScenarioProjection, SwarmBufferGuardCapabilities {
}
