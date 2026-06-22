package io.pockethive.scenarios.validation;

import com.fasterxml.jackson.annotation.JsonValue;

public enum BundleValidationSource {
    UPLOADED_ZIP("uploaded-zip"),
    SCENARIO_MANAGER("scenario-manager");

    private final String wireValue;

    BundleValidationSource(String wireValue) {
        this.wireValue = wireValue;
    }

    @JsonValue
    public String wireValue() {
        return wireValue;
    }
}
