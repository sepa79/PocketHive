package io.pockethive.artemis.topology;

import io.pockethive.artemis.api.ArtemisWorkIoType;
import io.pockethive.topology.work.WorkResourceIdentity;
import java.util.Objects;

/**
 * Responsibility: define Artemis native resource kinds and validate their owning adapter.
 * Must not: build physical names, query the broker or decide lifecycle outcomes.
 * Contract: RESP-ARTEMIS-RESOURCE-NAMES — docs/architecture/runtime-responsibilities.md#resp-artemis-resource-names.
 */
public enum ArtemisResourceKind {
    ADDRESS, QUEUE;

    public WorkResourceIdentity identity(String name) {
        return new WorkResourceIdentity(ArtemisWorkIoType.ARTEMIS.name(), name(), name);
    }

    public static ArtemisResourceKind require(WorkResourceIdentity resource) {
        Objects.requireNonNull(resource, "resource");
        if (!ArtemisWorkIoType.ARTEMIS.name().equals(resource.owner())) {
            throw new IllegalArgumentException("Expected Artemis resource owner");
        }
        return valueOf(resource.kind());
    }
}
