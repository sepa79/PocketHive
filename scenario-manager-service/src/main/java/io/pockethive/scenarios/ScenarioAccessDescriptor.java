package io.pockethive.scenarios;

/**
 * Responsibility: Carry the canonical scenario and bundle identity used for authorization.
 * Must not: Decide authorization or infer missing identities.
 * Contract: docs/scenarios/SCENARIO_MANAGER_BUNDLE_REST.md.
 */
public record ScenarioAccessDescriptor(String scenarioId, String bundlePath, String folderPath) { }
