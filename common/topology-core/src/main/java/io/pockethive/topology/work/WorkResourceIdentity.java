package io.pockethive.topology.work;

import java.util.Objects;
/**
 * Responsibility: retain an adapter-owned Work resource identity without interpreting its native kind.
 * Must not: choose an adapter, reconstruct consumer-specific names or decide swarm lifecycle outcomes.
 * Contract: RESP-WORK-RESOURCE-NAMES — docs/architecture/runtime-responsibilities.md#resp-work-resource-names.
 */
public record WorkResourceIdentity(String owner, String kind, String name) {
    public WorkResourceIdentity {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(name, "name");
        if (owner.isBlank() || kind.isBlank() || name.isBlank()) throw new IllegalArgumentException("Explicit resource identity required");
    }
}
