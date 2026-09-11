package io.pockethive.topology.work;

/**
 * Responsibility: resolve swarm topology settings and logical Work resource names.
 * Must not: expose clients, configure adapters or provision resources.
 * Contract: RESP-WORK-RESOURCE-NAMES — docs/architecture/runtime-responsibilities.md#resp-work-resource-names.
 */
public interface WorkResourceNamesPort {
    WorkTopologySettings forSwarm(String swarmId);
    WorkAddress address(String exchange, String prefix, String suffix);
    String exchangeName(String configuredName);
    String queueName(String prefix, String suffix);
}
