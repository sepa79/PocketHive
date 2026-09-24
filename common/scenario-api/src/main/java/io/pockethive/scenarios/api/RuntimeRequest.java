package io.pockethive.scenarios.api;

/**
 * Responsibility: Carry the swarm identity requested for scenario runtime materialisation.
 * Must not: Validate or materialize runtime files.
 * Contract: RESP-SCENARIO-HTTP-CONTRACT — docs/architecture/runtime-responsibilities.md#resp-scenario-http-contract; docs/scenarios/SCENARIO_MANAGER_BUNDLE_REST.md.
 */
public record RuntimeRequest(String swarmId) {
}
