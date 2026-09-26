package io.pockethive.swarmcontroller;

import io.pockethive.swarm.model.lifecycle.WorkloadState;

/**
 * Responsibility: Represent one immutable observation of Swarm Controller command readiness.
 * Must not: Query lifecycle state, publish results, or mutate readiness.
 * Contract: RESP-SWARM-COMMAND-ADMISSION — docs/architecture/runtime-responsibilities.md#resp-swarm-command-admission.
 */
record SwarmCommandReadinessSnapshot(
    boolean initialized,
    boolean ready,
    boolean pendingConfigUpdates,
    WorkloadState workloadState) {

  boolean acceptsStart() {
    return initialized && ready && !pendingConfigUpdates;
  }

  boolean acceptsStop() {
    return initialized;
  }

  boolean acceptsConfig(boolean requireRunning) {
    return initialized && !pendingConfigUpdates
        && (!requireRunning || workloadState == WorkloadState.RUNNING);
  }
}
