package io.pockethive.scenarios.validation;

import com.fasterxml.jackson.annotation.JsonValue;

public enum ValidationSeverity {
    ERROR("error"),
    WARNING("warning");

    private final String wireValue;

    ValidationSeverity(String wireValue) {
        this.wireValue = wireValue;
    }

    @JsonValue
    public String wireValue() {
        return wireValue;
    }
}
