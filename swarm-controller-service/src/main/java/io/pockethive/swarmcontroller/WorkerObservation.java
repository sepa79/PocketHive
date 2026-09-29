package io.pockethive.swarmcontroller;

import java.time.Instant;

/**
 * Responsibility: Expose immutable observed enablement and freshness from the readiness owner.
 * Must not: Store mutable state, read a clock or decide lifecycle command outcomes.
 * Contract: RESP-SWARM-OBSERVATION — docs/architecture/runtime-responsibilities.md#resp-swarm-observation.
 */
public record WorkerObservation(Instant lastSeenAt, boolean enabled, boolean stale) {
}
