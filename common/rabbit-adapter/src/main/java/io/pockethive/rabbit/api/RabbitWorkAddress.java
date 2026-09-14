package io.pockethive.rabbit.api;


/**
 * Responsibility: carry the resolved exchange, queue and routing key of one Rabbit Work connection.
 * Must not: infer relationships between addresses, resolve names or provision resources.
 * Contract: RESP-WORK-RESOURCE-NAMES — docs/architecture/runtime-responsibilities.md#resp-work-resource-names.
 */
public record RabbitWorkAddress(String exchange, String queue, String routingKey) { }
