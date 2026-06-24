package io.pockethive.scenarios.validation;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum ValidationSeverity {
    ERROR("error"),
    WARNING("warning");

    private final String wireValue;

    ValidationSeverity(String wireValue) {
        this.wireValue = wireValue;
    }

    @JsonCreator
    public static ValidationSeverity fromWireValue(String wireValue) {
        for (ValidationSeverity severity : values()) {
            if (severity.wireValue.equals(wireValue)) {
                return severity;
            }
        }
        throw new IllegalArgumentException("Unknown validation severity: " + wireValue);
    }

    @JsonValue
    public String wireValue() {
        return wireValue;
    }
}
