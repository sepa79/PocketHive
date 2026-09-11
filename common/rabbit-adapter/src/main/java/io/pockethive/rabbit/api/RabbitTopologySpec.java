package io.pockethive.rabbit.api;


import java.util.List;

/**
 * Responsibility: carry explicit queue and binding declarations from domain composition.
 * Must not: declare resources or expose Spring AMQP declarables.
 * Contract: docs/architecture/work-plane-boundaries.md#3-ports-owners-and-state-transitions.
 */
public record RabbitTopologySpec(List<RabbitQueueSpec> queues, List<RabbitBindingSpec> bindings) {
    public RabbitTopologySpec {
        queues = List.copyOf(queues);
        bindings = List.copyOf(bindings);
    }
}
