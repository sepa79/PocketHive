package io.pockethive.orchestrator.app;

import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Responsibility: parse a debug tap's requested direction at the application boundary.
 * Must not: select a transport or resolve source addresses.
 * Contract: RESP-WORK-RESOURCE-NAMES — docs/architecture/runtime-responsibilities.md#resp-work-resource-names.
 */
enum DebugTapDirection {
    IN, OUT;

    static DebugTapDirection from(String raw) {
        if (raw == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "direction is required");
        String value = raw.trim().toUpperCase(Locale.ROOT);
        for (DebugTapDirection direction : values()) {
            if (direction.name().equals(value)) return direction;
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "direction must be IN or OUT");
    }
}
