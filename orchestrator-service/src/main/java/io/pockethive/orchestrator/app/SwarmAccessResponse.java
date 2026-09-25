package io.pockethive.orchestrator.app;

import java.util.List;

/**
 * Responsibility: carry the visible swarm permission collection.
 * Must not: store grant policy.
 * Contract: RESP-SWARM-ACCESS-PROJECTION — docs/architecture/runtime-responsibilities.md#resp-swarm-access-projection.
 */
public record SwarmAccessResponse(List<SwarmAccessView> swarms) {
    public SwarmAccessResponse { swarms = List.copyOf(swarms); }
}
