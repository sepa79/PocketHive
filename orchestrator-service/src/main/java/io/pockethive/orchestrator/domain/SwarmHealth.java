package io.pockethive.orchestrator.domain;

/**
 * Health of a swarm as reported by the swarm controller aggregates.
 */
public enum SwarmHealth {
    UNKNOWN,
    RUNNING,
    DEGRADED,
    FAILED
}
