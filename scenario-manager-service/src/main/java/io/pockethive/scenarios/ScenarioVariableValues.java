package io.pockethive.scenarios;

import java.util.Map;

/**
 * Responsibility: Carry canonical global and SUT-scoped variable value maps.
 * Must not: Merge, validate, or resolve those values.
 * Contract: docs/scenarios/SCENARIO_VARIABLES.md.
 */
public record ScenarioVariableValues(
    Map<String, Map<String, Object>> global,
    Map<String, Map<String, Map<String, Object>>> sut
) { }
