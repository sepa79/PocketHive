package io.pockethive.controlplane.consumer;

import io.pockethive.control.ControlSignal;
import io.pockethive.controlplane.ControlPlaneIdentity;
import io.pockethive.controlplane.routing.ControlPlaneRouting;

import java.time.Instant;
import java.util.Objects;

/**
 * Parsed control signal plus metadata supplied by the consumer infrastructure.
 */
public record ControlSignalEnvelope(ControlSignal signal, String routingKey, String payload, Instant receivedAt) {

    public ControlSignalEnvelope {
        Objects.requireNonNull(signal, "signal");
        Objects.requireNonNull(routingKey, "routingKey");
        Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(receivedAt, "receivedAt");
    }

    public boolean targets(ControlPlaneIdentity identity) {
        if (identity == null) {
            return false;
        }
        ControlPlaneRouting.RoutingKey key = ControlPlaneRouting.parseSignal(routingKey);
        if (key == null) {
            return false;
        }
        return key.matchesSwarm(identity.swarmId())
            && key.matchesRole(identity.role())
            && key.matchesInstance(identity.instanceId());
    }

    public boolean originatedFrom(ControlPlaneIdentity identity) {
        if (identity == null) {
            return false;
        }
        String origin = normalise(signal.origin());
        if (origin == null) {
            return false;
        }
        String instanceId = normalise(identity.instanceId());
        return Objects.equals(origin, instanceId);
    }

    private static String normalise(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
