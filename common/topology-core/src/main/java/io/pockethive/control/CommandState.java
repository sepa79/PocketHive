package io.pockethive.control;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Structured state echoed by confirmations to describe post-command conditions.
 */
public record CommandState(
    String status,
    Boolean enabled,
    Map<String, Object> details
) {

    public CommandState {
        status = trimToNull(status);
        details = immutableDetailsOrNull(details);
    }

    public static CommandState status(String status) {
        return new CommandState(status, null, null);
    }

    private static Map<String, Object> immutableDetailsOrNull(Map<String, Object> details) {
        if (details == null || details.isEmpty()) {
            return null;
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(details));
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
