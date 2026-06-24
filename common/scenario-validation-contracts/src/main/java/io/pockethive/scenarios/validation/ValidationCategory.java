package io.pockethive.scenarios.validation;

import com.fasterxml.jackson.annotation.JsonCreator;
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

    @JsonCreator
    public static ValidationCategory fromWireValue(String wireValue) {
        for (ValidationCategory category : values()) {
            if (category.wireValue.equals(wireValue)) {
                return category;
            }
        }
        throw new IllegalArgumentException("Unknown validation category: " + wireValue);
    }

    @JsonValue
    public String wireValue() {
        return wireValue;
    }
}
