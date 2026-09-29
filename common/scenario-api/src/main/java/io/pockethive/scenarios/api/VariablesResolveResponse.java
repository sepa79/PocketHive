package io.pockethive.scenarios.api;

import java.util.List;
import java.util.Map;

/**
 * Responsibility: Carry resolved scenario variables over HTTP.
 * Must not: Resolve, validate, or persist variables.
 * Contract: RESP-SCENARIO-HTTP-CONTRACT — docs/architecture/runtime-responsibilities.md#resp-scenario-http-contract; docs/scenarios/SCENARIO_VARIABLES.md.
 */
public record VariablesResolveResponse(
    String profileId,
    String sutId,
    Map<String, Object> vars,
    List<String> warnings
) {
}
