package io.pockethive.scenarios;

import com.fasterxml.jackson.annotation.JsonCreator;
import java.util.Locale;

/**
 * Responsibility: Define and parse the canonical scope of a scenario variable.
 * Must not: Resolve variable values or infer a scope.
 * Contract: docs/scenarios/SCENARIO_VARIABLES.md.
 */
public enum ScenarioVariableScope {
    GLOBAL,
    SUT;

    @JsonCreator
    public static ScenarioVariableScope fromJson(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            return null;
        }
        return ScenarioVariableScope.valueOf(normalized.toUpperCase(Locale.ROOT));
    }
}
