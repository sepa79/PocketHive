package io.pockethive.scenarios;

import java.util.List;

/**
 * Responsibility: Represent the canonical variables.yaml document shape.
 * Must not: Parse, validate, or resolve variable values.
 * Contract: RESP-SCENARIO-VALIDATE — docs/architecture/runtime-responsibilities.md#resp-scenario-validate; docs/scenarios/SCENARIO_VARIABLES.md.
 */
public record VariablesDocument(
    int version,
    List<ScenarioVariableDefinition> definitions,
    List<ScenarioVariablesProfile> profiles,
    ScenarioVariableValues values
) {
    public static final int CURRENT_VERSION = 1;
}
