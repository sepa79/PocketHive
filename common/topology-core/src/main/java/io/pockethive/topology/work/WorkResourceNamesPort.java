package io.pockethive.topology.work;

/**
 * Responsibility: resolve swarm topology settings and logical Work resource names.
 * Must not: expose clients, configure adapters or provision resources.
 * Contract: RESP-WORK-RESOURCE-NAMES — docs/architecture/runtime-responsibilities.md#resp-work-resource-names.
 */
public interface WorkResourceNamesPort {
    WorkTopologySettings forSwarm(String swarmId);
    String exchangeName(String configuredName);
    String queueName(String prefix, String suffix);
}
