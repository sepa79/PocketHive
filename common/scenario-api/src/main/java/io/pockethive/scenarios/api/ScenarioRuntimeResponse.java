package io.pockethive.scenarios.api;

/**
 * Responsibility: Carry the result of scenario runtime materialisation over HTTP.
 * Must not: Own runtime lifecycle or filesystem state.
 * Contract: RESP-SCENARIO-HTTP-CONTRACT — docs/architecture/runtime-responsibilities.md#resp-scenario-http-contract; docs/scenarios/SCENARIO_MANAGER_BUNDLE_REST.md.
 */
public record ScenarioRuntimeResponse(String scenarioId, String swarmId, String runtimeDir) {
}
