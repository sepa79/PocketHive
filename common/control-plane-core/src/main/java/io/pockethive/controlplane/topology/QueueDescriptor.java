package io.pockethive.controlplane.topology;

import java.util.Objects;
import java.util.Set;

/**
 * Describes a queue and the binding keys required to attach it to an exchange.
 */
public record QueueDescriptor(String name, Set<String> bindings, QueueScope scope) {

    public QueueDescriptor {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        scope = Objects.requireNonNull(scope, "scope");
        bindings = bindings == null || bindings.isEmpty() ? Set.of() : Set.copyOf(bindings);
    }

    public static QueueDescriptor control(String name, Set<String> bindings) {
        return new QueueDescriptor(name, bindings, QueueScope.CONTROL);
    }

    public static QueueDescriptor traffic(String name, Set<String> bindings) {
        return new QueueDescriptor(name, bindings, QueueScope.TRAFFIC);
    }
}
