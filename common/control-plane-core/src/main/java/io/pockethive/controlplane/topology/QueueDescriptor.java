package io.pockethive.controlplane.topology;

import java.util.Set;

/**
 * Describes a queue and the binding keys required to attach it to an exchange.
 */
public record QueueDescriptor(String name, Set<String> bindings) {

    public QueueDescriptor {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        bindings = bindings == null || bindings.isEmpty() ? Set.of() : Set.copyOf(bindings);
    }
}
