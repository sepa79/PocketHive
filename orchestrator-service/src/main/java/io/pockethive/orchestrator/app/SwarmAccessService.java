package io.pockethive.orchestrator.app;

import io.pockethive.auth.contract.AuthenticatedUserDto;
import io.pockethive.orchestrator.auth.OrchestratorAuthorization;
import io.pockethive.orchestrator.domain.Swarm;
import org.springframework.stereotype.Service;

/**
 * Responsibility: evaluate existing per-swarm permissions for commands and read projections.
 * Must not: copy grant rules or infer lifecycle eligibility.
 * Contract: RESP-SWARM-ACCESS-PROJECTION — docs/architecture/runtime-responsibilities.md#resp-swarm-access-projection.
 */
@Service
public class SwarmAccessService {
    private final OrchestratorAuthorization authorization;
    private final SwarmTemplateScopeResolver scopes;
    public SwarmAccessService(OrchestratorAuthorization authorization, SwarmTemplateScopeResolver scopes) {
        this.authorization = authorization;
        this.scopes = scopes;
    }
    public boolean canRead(AuthenticatedUserDto user, Swarm swarm) {
        return user == null || authorization.canRead(user, scopes.resolve(swarm));
    }
    public boolean canRun(AuthenticatedUserDto user, Swarm swarm) {
        return user == null || authorization.canRun(user, scopes.resolve(swarm));
    }
    public boolean canManage(AuthenticatedUserDto user, Swarm swarm) {
        return user == null || authorization.canManage(user, scopes.resolve(swarm));
    }
}
