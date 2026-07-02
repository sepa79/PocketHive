package io.pockethive.capabilities;

import java.util.Arrays;
import java.util.Optional;
import java.util.stream.Collectors;

public enum CapabilityConfigType {
    STRING("string", "string"),
    BOOLEAN("boolean", "boolean"),
    NUMBER("number", "number"),
    INTEGER("integer", "integer"),
    JSON("json", "object or array");

    private final String wireValue;
    private final String description;

    CapabilityConfigType(String wireValue, String description) {
        this.wireValue = wireValue;
        this.description = description;
    }

    public String wireValue() {
        return wireValue;
    }

    public String description() {
        return description;
    }

    public static Optional<CapabilityConfigType> fromWireValue(String value) {
        if (value == null) {
            return Optional.empty();
        }
        return Arrays.stream(values())
            .filter(type -> type.wireValue.equals(value))
            .findFirst();
    }

    public static String allowedWireValues() {
        return Arrays.stream(values())
            .map(CapabilityConfigType::wireValue)
            .collect(Collectors.joining(", "));
    }

}
