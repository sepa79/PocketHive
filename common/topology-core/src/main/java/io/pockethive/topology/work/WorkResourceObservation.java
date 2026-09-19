package io.pockethive.topology.work;

import java.util.Objects;
import java.util.OptionalLong;
/**
 * Responsibility: project resource observations from the owning Work adapter.
 * Must not: choose an adapter, reconstruct consumer-specific names or decide swarm lifecycle outcomes.
 * Contract: RESP-WORK-RESOURCE-NAMES — docs/architecture/runtime-responsibilities.md#resp-work-resource-names.
 */
public record WorkResourceObservation(long messages, int consumers, OptionalLong oldestAgeSeconds) {
    public WorkResourceObservation {
        if (messages < 0 || consumers < 0) throw new IllegalArgumentException("Negative resource observation");
        Objects.requireNonNull(oldestAgeSeconds, "oldestAgeSeconds");
    }
}
