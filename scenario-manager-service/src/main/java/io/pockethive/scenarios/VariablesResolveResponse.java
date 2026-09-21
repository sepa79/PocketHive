package io.pockethive.scenarios;

import java.util.List;
import java.util.Map;

/**
 * Responsibility: Carry resolved scenario variables over HTTP.
 * Must not: Resolve, validate, or persist variables.
 * Contract: docs/scenarios/SCENARIO_VARIABLES.md.
 */
public record VariablesResolveResponse(
    String profileId,
    String sutId,
    Map<String, Object> vars,
    List<String> warnings
) {
}
