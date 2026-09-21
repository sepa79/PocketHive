package io.pockethive.scenarios;

/**
 * Responsibility: Carry the result of scenario runtime materialisation over HTTP.
 * Must not: Own runtime lifecycle or filesystem state.
 * Contract: docs/scenarios/SCENARIO_MANAGER_BUNDLE_REST.md.
 */
public record ScenarioRuntimeResponse(String scenarioId, String swarmId, String runtimeDir) {
}
