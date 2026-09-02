package io.pockethive.swarmcontroller;

import io.pockethive.swarm.model.lifecycle.WorkloadState;

/**
 * Responsibility: Represent one immutable observation of Swarm Controller command readiness.
 * Must not: Query lifecycle state, publish results, or mutate readiness.
 * Contract: Command gates evaluate one captured readiness observation.
 */
record SwarmCommandReadinessSnapshot(
    boolean initialized,
    boolean ready,
    boolean pendingConfigUpdates,
    WorkloadState workloadState) {

  boolean accepts(boolean requireRunning) {
    return initialized
        && ready
        && !pendingConfigUpdates
        && (!requireRunning || workloadState == WorkloadState.RUNNING);
  }
}
