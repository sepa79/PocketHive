package io.pockethive.rabbit.api;


import java.util.Map;
import java.util.Objects;

/**
 * Responsibility: retain explicit topic exchange declaration parameters used by both planes.
 * Must not: infer exchange names or perform broker operations.
 * Contract: docs/architecture/work-plane-boundaries.md#3-ports-owners-and-state-transitions.
 */
public record RabbitExchangeSpec(String name, boolean durable, boolean autoDelete, Map<String, Object> arguments) {
    public RabbitExchangeSpec {
        Objects.requireNonNull(name, "name");
        if (name.isBlank()) throw new IllegalArgumentException("Exchange name must not be blank");
        arguments = Map.copyOf(arguments);
    }
}
