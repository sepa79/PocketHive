package io.pockethive.controlplane.consumer;

import io.pockethive.control.CommandTarget;
import io.pockethive.control.ControlSignal;
import io.pockethive.controlplane.ControlPlaneIdentity;

import java.time.Instant;
import java.util.Objects;

/**
 * Parsed control signal plus metadata supplied by the consumer infrastructure.
 */
public record ControlSignalEnvelope(ControlSignal signal, String routingKey, Instant receivedAt) {

    public ControlSignalEnvelope {
        Objects.requireNonNull(signal, "signal");
        Objects.requireNonNull(routingKey, "routingKey");
        Objects.requireNonNull(receivedAt, "receivedAt");
    }

    public boolean targets(ControlPlaneIdentity identity) {
        if (identity == null) {
            return false;
        }
        CommandTarget target = signal.commandTarget();
        if (target == null) {
            return false;
        }
        return switch (target) {
            case INSTANCE -> identity.matchesInstance(signal.role(), signal.instance());
            case ROLE -> identity.matchesRole(signal.role());
            case SWARM -> Objects.equals(identity.swarmId(), normalise(signal.swarmId()));
            case ALL -> true;
        };
    }

    private static String normalise(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
