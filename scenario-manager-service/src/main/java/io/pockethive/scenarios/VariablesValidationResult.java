package io.pockethive.scenarios;

import java.util.List;

/**
 * Responsibility: Carry canonical non-blocking variables validation warnings.
 * Must not: Validate variables or decide whether they are persisted.
 * Contract: docs/scenarios/SCENARIO_VARIABLES.md.
 */
public record VariablesValidationResult(List<String> warnings) { }
