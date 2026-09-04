package io.pockethive.scenarios;

/**
 * Responsibility: Represent one declared scenario variable.
 * Must not: Validate or resolve the variable value.
 * Contract: docs/scenarios/SCENARIO_VARIABLES.md.
 */
public record ScenarioVariableDefinition(
    String name,
    ScenarioVariableScope scope,
    ScenarioVariableType type,
    Boolean required
) { }
