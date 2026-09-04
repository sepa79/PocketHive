package io.pockethive.scenarios;

import java.util.List;

/**
 * Responsibility: Carry the result of writing a scenario variables document over HTTP.
 * Must not: Validate or persist variables.
 * Contract: docs/scenarios/SCENARIO_VARIABLES.md.
 */
public record VariablesWriteResponse(String status, List<String> warnings) {
}
