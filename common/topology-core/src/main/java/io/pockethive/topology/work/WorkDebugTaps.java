package io.pockethive.topology.work;

/**
 * Responsibility: create a temporary capture of one resolved channel through its selected adapter.
 * Must not: resolve logical names, choose another adapter or mutate swarm lifecycle.
 * Contract: RESP-WORK-RESOURCE-NAMES — docs/architecture/runtime-responsibilities.md#resp-work-resource-names.
 */
public interface WorkDebugTaps {
    WorkDebugTap open(String swarmId, String role, String tapId, WorkChannelAddress source, int ttlSeconds, int maxItems);
}
