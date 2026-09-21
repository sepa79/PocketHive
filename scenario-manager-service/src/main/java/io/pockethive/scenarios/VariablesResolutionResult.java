package io.pockethive.scenarios;

import java.util.List;
import java.util.Map;

/**
 * Responsibility: Carry resolved scenario variables and canonical validation warnings.
 * Must not: Select profiles, SUTs, or fallback values.
 * Contract: docs/scenarios/SCENARIO_VARIABLES.md.
 */
public record VariablesResolutionResult(Map<String, Object> vars, List<String> warnings) { }
