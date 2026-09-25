package io.pockethive.orchestrator.app;

/**
 * Responsibility: carry a caller-specific permission projection for one swarm.
 * Must not: authorize command execution.
 * Contract: RESP-SWARM-ACCESS-PROJECTION — docs/architecture/runtime-responsibilities.md#resp-swarm-access-projection.
 */
public record SwarmAccessView(String swarmId, boolean canRun, boolean canManage) {}
