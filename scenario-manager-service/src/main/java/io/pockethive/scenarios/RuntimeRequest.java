package io.pockethive.scenarios;

/**
 * Responsibility: Carry the swarm identity requested for scenario runtime materialisation.
 * Must not: Validate or materialize runtime files.
 * Contract: docs/scenarios/SCENARIO_MANAGER_BUNDLE_REST.md.
 */
public record RuntimeRequest(String swarmId) {
}
