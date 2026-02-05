package io.pockethive.worker.sdk.api;

public enum WorkPayloadEncoding {
    UTF_8("utf-8"),
    BASE64("base64");

    private final String wireValue;

    WorkPayloadEncoding(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }

    public static WorkPayloadEncoding fromWireValue(String value) {
        if (value == null || value.isBlank()) {
            return UTF_8;
        }
        String normalized = value.trim().toLowerCase(java.util.Locale.ROOT);
        if ("base64".equals(normalized)) {
            return BASE64;
        }
        return UTF_8;
    }
}
