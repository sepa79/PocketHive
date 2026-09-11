package io.pockethive.topology.work;

/**
 * Responsibility: own swarm topology settings and the prefix-to-logical-queue mapping.
 * Must not: read process configuration, validate adapter tuning or access infrastructure.
 * Contract: RESP-WORK-RESOURCE-NAMES — docs/architecture/runtime-responsibilities.md#resp-work-resource-names.
 */
public final class PrefixedWorkResourceNames implements WorkResourceNamesPort {
    private static final String SWARM_PREFIX = "ph.";
    private static final String HIVE_SUFFIX = ".hive";

    @Override
    public WorkTopologySettings forSwarm(String swarmId) {
        String prefix = SWARM_PREFIX + name(swarmId, "swarm id");
        return new WorkTopologySettings(prefix, prefix + HIVE_SUFFIX);
    }

    @Override
    public String exchangeName(String configuredName) {
        return name(configuredName, "traffic exchange");
    }

    private static String name(String value, String label) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(label + " must not be null or blank");
        return value.trim();
    }

    @Override
    public String queueName(String prefix, String suffix) {
        return name(prefix, "traffic queue prefix") + "." + name(suffix, "traffic queue suffix");
    }
}
