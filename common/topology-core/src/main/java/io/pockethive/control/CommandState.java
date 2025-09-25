package io.pockethive.control;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Structured state echoed by confirmations to describe post-command conditions.
 */
public record CommandState(
    String status,
    ConfirmationScope scope,
    Boolean enabled,
    Map<String, Object> details
) {

    public CommandState {
        if (status != null) {
            status = status.trim();
            if (status.isEmpty()) {
                status = null;
            }
        }
        if (details != null && !details.isEmpty()) {
            details = Collections.unmodifiableMap(new LinkedHashMap<>(details));
        } else {
            details = null;
        }
        scope = scope == null ? null : new ConfirmationScope(scope.swarmId(), scope.role(), scope.instance());
    }

    public static CommandState status(String status) {
        return new CommandState(status, null, null, null);
    }
}

