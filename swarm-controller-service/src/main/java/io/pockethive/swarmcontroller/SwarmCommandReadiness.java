package io.pockethive.swarmcontroller;

import java.util.Objects;
import java.util.function.BooleanSupplier;

/**
 * Responsibility: Read the canonical lifecycle facts used to gate Swarm Controller commands.
 * Must not: Reject commands, publish results, or mutate lifecycle state.
 * Contract: START, STOP, and config workflows consume the same readiness observation owner.
 */
final class SwarmCommandReadiness {

  private final SwarmLifecycle lifecycle;
  private final BooleanSupplier initialized;

  SwarmCommandReadiness(SwarmLifecycle lifecycle, BooleanSupplier initialized) {
    this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
    this.initialized = Objects.requireNonNull(initialized, "initialized");
  }

  SwarmCommandReadinessSnapshot snapshot() {
    return new SwarmCommandReadinessSnapshot(
        initialized.getAsBoolean(),
        lifecycle.isReadyForWork(),
        lifecycle.hasPendingConfigUpdates(),
        lifecycle.getWorkloadState());
  }
}
