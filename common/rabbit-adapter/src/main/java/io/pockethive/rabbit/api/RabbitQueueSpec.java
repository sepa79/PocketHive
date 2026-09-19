package io.pockethive.rabbit.api;


import java.util.Map;
import java.util.Objects;

/**
 * Responsibility: retain explicit Rabbit queue declaration parameters.
 * Must not: invent names, defaults or create broker resources.
 * Contract: RESP-RABBIT-RESOURCES — docs/architecture/runtime-responsibilities.md#resp-rabbit-resources.
 */
public record RabbitQueueSpec(String name, boolean durable, boolean exclusive, boolean autoDelete,
                              Map<String, Object> arguments) {
    public RabbitQueueSpec {
        Objects.requireNonNull(name, "name");
        if (name.isBlank()) throw new IllegalArgumentException("Queue name must not be blank");
        arguments = Map.copyOf(arguments);
    }
}
