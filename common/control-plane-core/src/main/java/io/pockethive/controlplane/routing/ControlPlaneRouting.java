package io.pockethive.controlplane.routing;

import io.pockethive.control.ConfirmationScope;
import io.pockethive.control.ControlScope;
import java.util.Arrays;
import java.util.Objects;
import java.util.StringJoiner;

/**
 * Utility methods for constructing control-plane routing keys.
 */
public final class ControlPlaneRouting {

    private ControlPlaneRouting() {
    }

    public static String signal(String signal, String swarmId, String role, String instanceId) {
        return join(
            "signal",
            ControlScope.requireSegment("signal type", signal),
            ControlScope.requireSegment("signal swarmId", swarmId),
            ControlScope.requireSegment("signal role", role),
            ControlScope.requireSegment("signal instance", instanceId));
    }

    public static String event(String category, String signal, ConfirmationScope scope) {
        return event(combineType(category, signal), scope);
    }

    public static String event(String type, ConfirmationScope scope) {
        Objects.requireNonNull(scope, "scope");
        return join("event",
            ControlScope.requireSegment("event type", type),
            ControlScope.requireSegment("event swarmId", scope.swarmId()),
            ControlScope.requireSegment("event role", scope.role()),
            ControlScope.requireSegment("event instance", scope.instance()));
    }

    private static String join(String... segments) {
        StringJoiner joiner = new StringJoiner(".");
        for (String segment : segments) {
            joiner.add(ControlScope.requireSegment("routing key segment", segment));
        }
        return joiner.toString();
    }

    private static String combineType(String category, String signal) {
        return ControlScope.requireSegment("event category", category)
            + "." + ControlScope.requireSegment("event type", signal);
    }

    private static String trimmedOrNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    public static RoutingKey parseSignal(String routingKey) {
        return parsePrefixed(routingKey, "signal");
    }

    public static RoutingKey parseEvent(String routingKey) {
        return parsePrefixed(routingKey, "event");
    }

    private static RoutingKey parsePrefixed(String routingKey, String expectedPrefix) {
        if (routingKey == null || expectedPrefix == null) {
            return null;
        }
        String prefix = expectedPrefix.trim();
        if (prefix.isEmpty()) {
            return null;
        }
        String token = prefix + ".";
        if (!routingKey.startsWith(token)) {
            return null;
        }
        String remainder = routingKey.substring(token.length());
        if (remainder.isBlank()) {
            return null;
        }
        String[] parts = remainder.split("\\.");
        if (parts.length < 4) {
            return null;
        }
        int len = parts.length;
        String instance = parts[len - 1];
        String role = parts[len - 2];
        String swarm = parts[len - 3];
        String type = String.join(".", Arrays.copyOf(parts, len - 3));
        if (type.isBlank() || swarm.isBlank() || role.isBlank() || instance.isBlank()) {
            return null;
        }
        return new RoutingKey(prefix, type, swarm, role, instance);
    }

    public record RoutingKey(String prefix, String type, String swarmId, String role, String instance) {

        public boolean matchesType(String expectedType) {
            String normalised = trimmedOrNull(expectedType);
            if (normalised == null) {
                return false;
            }
            if (ControlScope.isAll(normalised)) {
                return true;
            }
            String actual = trimmedOrNull(type);
            return Objects.equals(normalised, actual) || ControlScope.isAll(actual);
        }

        public boolean matchesRole(String expectedRole) {
            return matches(ControlScope.requireSegment("expected role", expectedRole), role);
        }

        public boolean matchesSwarm(String expectedSwarm) {
            return matches(ControlScope.requireSegment("expected swarmId", expectedSwarm), swarmId);
        }

        public boolean matchesInstance(String expectedInstance) {
            return matches(ControlScope.requireSegment("expected instance", expectedInstance), instance);
        }

        private boolean matches(String expected, String actual) {
            if (expected == null || actual == null) {
                return false;
            }
            if (ControlScope.isAll(expected) || ControlScope.isAll(actual)) {
                return true;
            }
            return Objects.equals(expected, actual);
        }
    }
}
