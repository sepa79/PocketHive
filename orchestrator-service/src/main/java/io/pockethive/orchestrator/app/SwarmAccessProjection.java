package io.pockethive.orchestrator.app;

import io.pockethive.auth.contract.AuthenticatedUserDto;
import io.pockethive.orchestrator.domain.Swarm;
import io.pockethive.orchestrator.domain.SwarmStore;
import java.util.Comparator;
import org.springframework.stereotype.Service;

/**
 * Responsibility: project permissions for the caller-visible swarm set.
 * Must not: implement grant rules or swallow scope-resolution failures.
 * Contract: RESP-SWARM-ACCESS-PROJECTION — docs/architecture/runtime-responsibilities.md#resp-swarm-access-projection.
 */
@Service
public class SwarmAccessProjection {
    private final SwarmStore store;
    private final SwarmAccessService access;
    public SwarmAccessProjection(SwarmStore store, SwarmAccessService access) {
        this.store = store;
        this.access = access;
    }
    public SwarmAccessResponse project(AuthenticatedUserDto user) {
        return new SwarmAccessResponse(store.all().stream()
            .filter(swarm -> access.canRead(user, swarm))
            .sorted(Comparator.comparing(Swarm::getId))
            .map(swarm -> new SwarmAccessView(swarm.getId(), access.canRun(user, swarm), access.canManage(user, swarm)))
            .toList());
    }
}
