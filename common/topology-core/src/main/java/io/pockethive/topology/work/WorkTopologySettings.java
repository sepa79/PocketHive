package io.pockethive.topology.work;

/**
 * Responsibility: carry the resolved swarm Work queue prefix and exchange to composition consumers.
 * Must not: reconstruct names, select a resolver or provision resources.
 * Contract: RESP-WORK-RESOURCE-NAMES — docs/architecture/runtime-responsibilities.md#resp-work-resource-names.
 */
public record WorkTopologySettings(String queuePrefix, String hiveExchange) {}
