package io.pockethive.scenarios.validation;

import com.fasterxml.jackson.annotation.JsonValue;

public enum ValidationCategory {
    BUNDLE("bundle"),
    SCENARIO("scenario"),
    TEMPLATES("templates"),
    VARIABLES("variables"),
    SUT("sut"),
    AUTH("auth"),
    CAPABILITIES("capabilities");

    private final String wireValue;

    ValidationCategory(String wireValue) {
        this.wireValue = wireValue;
    }

    @JsonValue
    public String wireValue() {
        return wireValue;
    }
}
