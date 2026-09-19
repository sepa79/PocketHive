package io.pockethive.scenarios;

import java.util.List;

/**
 * Responsibility: Represent the canonical variables.yaml document shape.
 * Must not: Parse, validate, or resolve variable values.
 * Contract: docs/scenarios/SCENARIO_VARIABLES.md.
 */
public record VariablesDocument(
    int version,
    List<ScenarioVariableDefinition> definitions,
    List<ScenarioVariablesProfile> profiles,
    ScenarioVariableValues values
) { }
