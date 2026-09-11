package io.pockethive.topology.work;

/**
 * Responsibility: carry the resolved exchange, queue and routing key of one Work connection.
 * Must not: infer relationships between addresses, resolve names or provision resources.
 * Contract: RESP-WORK-RESOURCE-NAMES — docs/architecture/work-plane-boundaries.md#physical-resource-naming-transfer.
 */
public record WorkAddress(String exchange, String queue, String routingKey) { }
