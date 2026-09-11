package io.pockethive.rabbit.api;


import java.util.Map;
import java.util.Objects;

/**
 * Responsibility: retain explicit Rabbit queue declaration parameters.
 * Must not: invent names, defaults or create broker resources.
 * Contract: docs/architecture/work-plane-boundaries.md#3-ports-owners-and-state-transitions.
 */
public record RabbitQueueSpec(String name, boolean durable, boolean exclusive, boolean autoDelete,
                              Map<String, Object> arguments) {
    public RabbitQueueSpec {
        Objects.requireNonNull(name, "name");
        if (name.isBlank()) throw new IllegalArgumentException("Queue name must not be blank");
        arguments = Map.copyOf(arguments);
    }
}
