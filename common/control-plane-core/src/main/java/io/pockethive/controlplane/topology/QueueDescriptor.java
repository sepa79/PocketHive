package io.pockethive.controlplane.topology;

import java.util.Objects;
import java.util.Set;

/**
 * Describes a queue and the binding keys required to attach it to an exchange.
 */
public record QueueDescriptor(String name, Set<String> bindings, ExchangeScope exchangeScope) {

    public QueueDescriptor {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        bindings = bindings == null || bindings.isEmpty() ? Set.of() : Set.copyOf(bindings);
        exchangeScope = Objects.requireNonNullElse(exchangeScope, ExchangeScope.CONTROL);
    }

    public QueueDescriptor(String name, Set<String> bindings) {
        this(name, bindings, ExchangeScope.CONTROL);
    }

    public enum ExchangeScope {
        CONTROL,
        TRAFFIC
    }
}
