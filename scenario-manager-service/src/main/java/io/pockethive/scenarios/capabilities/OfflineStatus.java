package io.pockethive.scenarios.capabilities;

import java.time.Instant;
import java.util.Collections;
import java.util.Map;

public record OfflineStatus(
        boolean present,
        String source,
        Instant lastModified,
        int roleCount,
        Map<String, Object> metadata) {
    public OfflineStatus {
        metadata = metadata == null ? Map.of() : Collections.unmodifiableMap(metadata);
    }

    public static OfflineStatus missing() {
        return new OfflineStatus(false, null, null, 0, Map.of());
    }
}
