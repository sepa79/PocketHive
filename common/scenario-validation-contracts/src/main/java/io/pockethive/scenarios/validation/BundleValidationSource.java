package io.pockethive.scenarios.validation;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum BundleValidationSource {
    UPLOADED_ZIP("uploaded-zip"),
    SCENARIO_MANAGER("scenario-manager");

    private final String wireValue;

    BundleValidationSource(String wireValue) {
        this.wireValue = wireValue;
    }

    @JsonCreator
    public static BundleValidationSource fromWireValue(String wireValue) {
        for (BundleValidationSource source : values()) {
            if (source.wireValue.equals(wireValue)) {
                return source;
            }
        }
        throw new IllegalArgumentException("Unknown bundle validation source: " + wireValue);
    }

    @JsonValue
    public String wireValue() {
        return wireValue;
    }
}
