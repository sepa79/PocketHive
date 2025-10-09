package io.pockethive.control;

import static io.pockethive.control.ConfirmationSupport.immutableDetailsOrNull;
import static io.pockethive.control.ConfirmationSupport.trimToNull;

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
}

