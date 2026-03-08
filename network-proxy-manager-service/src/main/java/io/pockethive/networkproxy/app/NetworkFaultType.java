package io.pockethive.networkproxy.app;

enum NetworkFaultType {
    LATENCY("latency"),
    BANDWIDTH("bandwidth"),
    TIMEOUT("timeout"),
    RESET_PEER("reset-peer");

    private final String id;

    NetworkFaultType(String id) {
        this.id = id;
    }

    static NetworkFaultType fromId(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("fault.type must not be blank");
        }
        String normalized = value.trim();
        for (NetworkFaultType type : values()) {
            if (type.id.equals(normalized)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unsupported network fault type: " + normalized);
    }
}
