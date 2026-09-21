package io.pockethive.scenarios;

import com.fasterxml.jackson.annotation.JsonCreator;
import java.util.Locale;

/**
 * Responsibility: Define and parse the canonical declared type of a scenario variable.
 * Must not: Coerce or validate variable values.
 * Contract: docs/scenarios/SCENARIO_VARIABLES.md.
 */
public enum ScenarioVariableType {
    STRING,
    INT,
    FLOAT,
    BOOL,
    OBJECT;

    @JsonCreator
    public static ScenarioVariableType fromJson(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            return null;
        }
        return ScenarioVariableType.valueOf(normalized.toUpperCase(Locale.ROOT));
    }
}
