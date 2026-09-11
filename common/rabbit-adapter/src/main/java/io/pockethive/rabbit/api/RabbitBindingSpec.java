package io.pockethive.rabbit.api;


import java.util.Map;
import java.util.Objects;

/**
 * Responsibility: retain an explicit queue-to-exchange binding and canonical routing key.
 * Must not: construct domain routing keys or operate a broker.
 * Contract: docs/architecture/work-plane-boundaries.md#3-ports-owners-and-state-transitions.
 */
public record RabbitBindingSpec(String queue, String exchange, String routingKey, Map<String, Object> arguments) {
    public RabbitBindingSpec {
        Objects.requireNonNull(queue, "queue");
        Objects.requireNonNull(exchange, "exchange");
        Objects.requireNonNull(routingKey, "routingKey");
        if (queue.isBlank() || exchange.isBlank()) throw new IllegalArgumentException("Binding resources must not be blank");
        arguments = Map.copyOf(arguments);
    }
}
