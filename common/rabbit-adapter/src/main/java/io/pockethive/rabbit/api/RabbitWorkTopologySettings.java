package io.pockethive.rabbit.api;


/**
 * Responsibility: carry the configured Rabbit Work queue prefix and exchange to its topology resolver.
 * Must not: reconstruct names, select a resolver or provision resources.
 * Contract: RESP-WORK-RESOURCE-NAMES — docs/architecture/runtime-responsibilities.md#resp-work-resource-names.
 */
public record RabbitWorkTopologySettings(String queuePrefix, String hiveExchange) {}
