package io.pockethive.topology.work;

import java.util.Set;
/**
 * Responsibility: resolve logical Work channels through the explicitly selected adapter.
 * Must not: choose an adapter, reconstruct consumer-specific names or decide swarm lifecycle outcomes.
 * Contract: RESP-WORK-RESOURCE-NAMES — docs/architecture/runtime-responsibilities.md#resp-work-resource-names.
 */
public interface WorkTopologyResolver {
    ResolvedWorkTopology resolve(String swarmId, Set<String> logicalChannels);
}
