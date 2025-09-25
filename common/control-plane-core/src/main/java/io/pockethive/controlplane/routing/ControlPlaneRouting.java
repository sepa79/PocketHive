package io.pockethive.controlplane.routing;

import io.pockethive.control.ConfirmationScope;
import java.util.Objects;
import java.util.StringJoiner;

/**
 * Utility methods for constructing control-plane routing keys.
 */
public final class ControlPlaneRouting {

    private ControlPlaneRouting() {
    }

    public static String signal(String signal, String swarmId, String role, String instanceId) {
        return join("sig", signal, segmentOrAll(swarmId), segmentOrAll(role), segmentOrAll(instanceId));
    }

    public static String event(String category, String signal, ConfirmationScope scope) {
        Objects.requireNonNull(scope, "scope");
        return join("ev", category, signal,
            segmentOrAll(scope.swarmId()),
            segmentOrAll(scope.role()),
            segmentOrAll(scope.instance()));
    }

    private static String segmentOrAll(String value) {
        if (value == null || value.isBlank()) {
            return "ALL";
        }
        return value.trim();
    }

    private static String join(String... segments) {
        StringJoiner joiner = new StringJoiner(".");
        for (String segment : segments) {
            if (segment == null || segment.isBlank()) {
                continue;
            }
            joiner.add(segment);
        }
        return joiner.toString();
    }
}
